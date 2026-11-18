package com.zmd.carhub

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Follows the car along a [Route]: where on the line it is, how far it has strayed, the next
 * manoeuvre and how far away it is, what is left of the trip, and when to speak.
 */
class NavEngine(val route: Route) {
    private val steps = route.steps
    private val stepStartM = DoubleArray(steps.size) { route.cum[steps[it].first.coerceIn(0, route.size - 1)] }
    private val stepStartMs = LongArray(steps.size).also { a ->
        for (i in 1 until a.size) a[i] = a[i - 1] + steps[i - 1].timeMs
    }
    private val totalMs: Long = steps.sumOf { it.timeMs }.let { if (it > 0) it else route.timeMs }

    /** The route segment the car is on (between point seg and seg + 1). */
    var seg = 0
        private set
    /** Metres driven along the route. */
    var alongM = 0.0
        private set
    /** Metres between the car and the route line. */
    var offM = 0.0
        private set
    var snapLat = route.lat(0)
        private set
    var snapLon = route.lon(0)
        private set

    private val said = HashMap<Int, Int>()

    fun update(lat: Double, lon: Double) {
        val last = route.size - 2
        if (last < 0) return
        var best = Double.MAX_VALUE
        var bestSeg = seg
        var bestT = 0.0
        val kx = cos(Math.toRadians(lat)) * 111320.0
        val ky = 110540.0
        fun scan(from: Int, to: Int) {
            for (i in from..to) {
                val sx = (route.lon(i + 1) - route.lon(i)) * kx
                val sy = (route.lat(i + 1) - route.lat(i)) * ky
                val px = (lon - route.lon(i)) * kx
                val py = (lat - route.lat(i)) * ky
                val len2 = sx * sx + sy * sy
                val t = if (len2 <= 0.0) 0.0 else ((px * sx + py * sy) / len2).coerceIn(0.0, 1.0)
                val dx = px - t * sx
                val dy = py - t * sy
                val d = sqrt(dx * dx + dy * dy)
                if (d < best) {
                    best = d
                    bestSeg = i
                    bestT = t
                }
            }
        }
        // Normally the car is just ahead of where it was; only when lost is the whole line searched.
        scan(max(0, seg - 3), min(last, seg + 60))
        if (best > 70.0) scan(0, last)
        seg = bestSeg
        offM = best
        alongM = route.cum[bestSeg] + bestT * (route.cum[bestSeg + 1] - route.cum[bestSeg])
        snapLat = route.lat(bestSeg) + (route.lat(bestSeg + 1) - route.lat(bestSeg)) * bestT
        snapLon = route.lon(bestSeg) + (route.lon(bestSeg + 1) - route.lon(bestSeg)) * bestT
    }

    /** The manoeuvre ahead of the car. */
    val nextIndex: Int
        get() {
            for (k in steps.indices) if (stepStartM[k] > alongM + 3.0) return k
            return steps.size - 1
        }
    val next: RouteStep get() = steps[nextIndex]
    /** The manoeuvre after [next], when it follows closely enough to be worth showing ("then..."). */
    val then: RouteStep?
        get() {
            val k = nextIndex
            if (k + 1 >= steps.size) return null
            return if (stepStartM[k + 1] - stepStartM[k] < 250.0) steps[k + 1] else null
        }
    val distToNextM: Double get() = max(0.0, stepStartM[nextIndex] - alongM)
    /** The street the car is driving on now. */
    val street: String get() = steps.getOrNull(nextIndex - 1)?.street ?: ""
    val remainingM: Double get() = max(0.0, route.lengthM - alongM)
    val remainingMs: Long
        get() {
            val k = (nextIndex - 1).coerceIn(0, steps.size - 1)
            val end = if (k + 1 < steps.size) stepStartM[k + 1] else route.lengthM
            val len = end - stepStartM[k]
            val part = if (len <= 0.0) 1.0 else ((alongM - stepStartM[k]) / len).coerceIn(0.0, 1.0)
            val done = stepStartMs[k] + (steps[k].timeMs * part).toLong()
            return max(0L, totalMs - done)
        }
    val arrived: Boolean get() = remainingM < 30.0

    /** Compass direction of the route where the car is, 0..360. */
    fun bearing(): Double = bearingOf(seg)

    private fun bearingOf(i: Int): Double {
        val j = i.coerceIn(0, route.size - 2)
        val la1 = Math.toRadians(route.lat(j))
        val la2 = Math.toRadians(route.lat(j + 1))
        val dLon = Math.toRadians(route.lon(j + 1) - route.lon(j))
        val y = sin(dLon) * cos(la2)
        val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** The point [m] metres along the route, as lat, lon, bearing (for the simulated drive). */
    fun pointAt(m: Double): DoubleArray {
        val d = m.coerceIn(0.0, route.lengthM)
        var lo = 0
        var hi = route.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (route.cum[mid] <= d) lo = mid else hi = mid
        }
        val len = route.cum[hi] - route.cum[lo]
        val t = if (len <= 0.0) 0.0 else (d - route.cum[lo]) / len
        return doubleArrayOf(
            route.lat(lo) + (route.lat(hi) - route.lat(lo)) * t,
            route.lon(lo) + (route.lon(hi) - route.lon(lo)) * t,
            bearingOf(lo)
        )
    }

    /** A realistic speed (m/s) for the stretch around [m] metres, from the route's own timing. */
    fun speedAt(m: Double): Double {
        var k = 0
        for (i in steps.indices) if (stepStartM[i] <= m) k = i
        val end = if (k + 1 < steps.size) stepStartM[k + 1] else route.lengthM
        val len = end - stepStartM[k]
        val ms = steps[k].timeMs
        val v = if (ms <= 0 || len <= 0.0) 12.0 else len / (ms / 1000.0)
        return v.coerceIn(7.0, 33.0)
    }

    /**
     * What to say now, or null. Each manoeuvre is announced far ahead, again closer, and at the
     * turn itself; the distances stretch with speed.
     */
    fun announcement(speedMps: Double): String? {
        val k = nextIndex
        val step = steps[k]
        val d = distToNextM
        val mask = said[k] ?: 0
        val now = max(30.0, speedMps * 3.0)
        val near = when {
            speedMps > 22 -> 600.0
            speedMps > 12 -> 350.0
            else -> 180.0
        }
        val far = if (speedMps > 22) 2000.0 else 1000.0
        return when {
            d <= now && mask and 4 == 0 -> {
                said[k] = mask or 15
                NavText.now(step)
            }
            d <= near && d > now * 1.4 && mask and 2 == 0 -> {
                said[k] = mask or 11
                NavText.ahead(step, d)
            }
            d <= far && d > near * 1.4 && mask and 1 == 0 -> {
                said[k] = mask or 9
                NavText.ahead(step, d)
            }
            d > far * 1.5 && mask and 8 == 0 -> {
                said[k] = mask or 8
                NavText.keepGoing(d, street)
            }
            else -> null
        }
    }
}

/** The words of the directions, on screen and spoken. */
object NavText {
    private val ORDINALS = listOf("", "الأول", "الثاني", "الثالث", "الرابع", "الخامس", "السادس", "السابع", "الثامن")

    fun distance(m: Double): String = when {
        m >= 10000 -> "${(m / 1000).roundToInt()} كم"
        m >= 1000 -> String.format(Locale.US, "%.1f كم", m / 1000)
        m >= 100 -> "${(m / 50).roundToInt() * 50} م"
        else -> "${max(10, (m / 10).roundToInt() * 10)} م"
    }

    private fun spokenDistance(m: Double): String = when {
        m >= 10000 -> "${(m / 1000).roundToInt()} كيلومتر"
        m >= 1500 -> String.format(Locale.US, "%.1f كيلومتر", m / 1000).replace(".0 ", " ")
        m >= 950 -> "كيلومتر واحد"
        m >= 100 -> "${(m / 50).roundToInt() * 50} متر"
        else -> "${max(10, (m / 10).roundToInt() * 10)} متر"
    }

    fun duration(ms: Long): String {
        val min = ((ms + 30000) / 60000).toInt()
        return when {
            min < 1 -> "أقل من دقيقة"
            min < 60 -> "$min د"
            else -> "${min / 60} س ${min % 60} د"
        }
    }

    /** What to do, without the street: "لف يمين". */
    fun action(s: RouteStep): String = when (s.sign) {
        MapRoute.LEFT -> "لف يسار"
        MapRoute.SLIGHT_LEFT -> "انحرف يسار"
        MapRoute.SHARP_LEFT -> "لف يسار بقوة"
        MapRoute.RIGHT -> "لف يمين"
        MapRoute.SLIGHT_RIGHT -> "انحرف يمين"
        MapRoute.SHARP_RIGHT -> "لف يمين بقوة"
        MapRoute.KEEP_LEFT -> "خليك على اليسار"
        MapRoute.KEEP_RIGHT -> "خليك على اليمين"
        MapRoute.ROUNDABOUT -> if (s.exit in 1..8) "بالفلكة اطلع من المخرج ${ORDINALS[s.exit]}" else "ادخل الفلكة"
        MapRoute.LEAVE_ROUNDABOUT -> "اطلع من الفلكة"
        MapRoute.U_TURN, MapRoute.U_TURN_LEFT, MapRoute.U_TURN_RIGHT -> "استدر وارجع"
        MapRoute.FINISH -> "وصلت"
        MapRoute.VIA -> "وصلت للمحطة"
        // «على طول», not «سيدة»: the voice reads «سيدة» as "lady".
        else -> "كمّل على طول"
    }

    /** The full line on screen: "لف يمين على شارع فلسطين". */
    fun line(s: RouteStep): String {
        val a = action(s)
        if (s.street.isEmpty() || s.sign == MapRoute.FINISH || s.sign == MapRoute.VIA) return a
        return "$a على ${s.street}"
    }

    /**
     * A street name the Arabic voice can read out: Arabic letters, digits and dashes only. Names
     * written in Kurdish or Latin letters come out garbled, so they stay on screen but are not spoken.
     */
    private fun speakable(name: String): String =
        if (name.isNotEmpty() && name.all { c ->
                c == ' ' || c == '-' || c == '.' || c == '،' || c.isDigit() ||
                    c.code in 0x0621..0x064A || c.code in 0x064B..0x0652
            }) name else ""

    /** The same line for the voice. */
    private fun spokenLine(s: RouteStep): String {
        val a = action(s)
        val street = speakable(s.street)
        if (street.isEmpty() || s.sign == MapRoute.FINISH || s.sign == MapRoute.VIA) return a
        return "$a على $street"
    }

    fun ahead(s: RouteStep, m: Double): String =
        if (s.sign == MapRoute.FINISH) "بعد ${spokenDistance(m)} توصل" else "بعد ${spokenDistance(m)}، ${spokenLine(s)}"

    fun now(s: RouteStep): String = if (s.sign == MapRoute.FINISH) "وصلت، بالسلامة" else spokenLine(s)

    fun keepGoing(m: Double, street: String): String {
        val name = speakable(street)
        return if (name.isEmpty()) "كمّل على طول ${spokenDistance(m)}" else "كمّل على $name ${spokenDistance(m)}"
    }
}

/**
 * Reads the directions aloud in Arabic. Uses the screen's default text-to-speech engine when it
 * speaks Arabic; otherwise every other installed engine is tried in turn (head units often ship
 * Google's engine next to a default one without Arabic). With no Arabic voice at all, each
 * announcement is a short double beep instead, so the driver still knows to look at the screen.
 */
class NavVoice(ctx: Context) {
    private val app = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    /** Engines still to try after the current one; filled when the first one has no Arabic. */
    private var others: ArrayDeque<String>? = null
    private var beeper: ToneGenerator? = null
    @Volatile private var closed = false

    /** True once an engine with an Arabic voice is ready. */
    @Volatile var available = false
        private set
    /** True once every engine has been tried (or one speaks Arabic). */
    @Volatile var checked = false
        private set
    /** The engine in use (package name), for the settings screen; null until found. */
    @Volatile var engine: String? = null
        private set
    /**
     * An installed engine knows Arabic but its Arabic voice is not downloaded yet (Google's engine
     * on the car, for example): [installVoiceIntent] opens its download page.
     */
    @Volatile var missingData = false
        private set
    private var missingEngine: String? = null

    init {
        open(null)
    }

    private fun open(name: String?) {
        if (closed) return
        val holder = arrayOfNulls<TextToSpeech>(1)
        val made = runCatching {
            // The ready call may come before the constructor returns: handle it on the next loop.
            holder[0] = TextToSpeech(app, { status -> main.post { ready(holder[0], status, name) } }, name)
            tts = holder[0]
        }
        if (made.isFailure) tryNext()
    }

    private fun ready(t: TextToSpeech?, status: Int, name: String?) {
        if (closed || t == null || t !== tts) {
            runCatching { t?.shutdown() }
            return
        }
        val engineName = name ?: runCatching { t.defaultEngine }.getOrNull()
        if (status == TextToSpeech.SUCCESS && speaksArabic(t, engineName)) {
            runCatching {
                t.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
            }
            pickVoice(t)
            // A touch slower than normal: clearer over road noise.
            runCatching { t.setSpeechRate(0.92f) }
            runCatching { t.setPitch(1.0f) }
            engine = engineName
            available = true
            missingData = false
            checked = true
            return
        }
        if (others == null) {
            val current = runCatching { t.defaultEngine }.getOrNull()
            val all = runCatching { t.engines.map { it.name } }.getOrDefault(emptyList())
            others = ArrayDeque(all.filter { it != current })
        }
        runCatching { t.shutdown() }
        tts = null
        tryNext()
    }

    private fun tryNext() {
        val next = others?.removeFirstOrNull()
        if (next == null || closed) {
            checked = true
            return
        }
        open(next)
    }

    /** Iraqi Arabic first, then plain Arabic and the regions engines usually ship. */
    private fun speaksArabic(t: TextToSpeech, engineName: String?): Boolean {
        for (loc in listOf(Locale("ar", "IQ"), Locale("ar"), Locale("ar", "XA"), Locale("ar", "SA"), Locale("ar", "EG"))) {
            val r = runCatching { t.setLanguage(loc) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (r >= TextToSpeech.LANG_AVAILABLE) return true
            if (r == TextToSpeech.LANG_MISSING_DATA && missingEngine == null) {
                missingData = true
                missingEngine = engineName
            }
        }
        return false
    }

    /**
     * The clearest Arabic voice the engine has downloaded: on the device (so it speaks with no
     * internet), best quality first, then Iraqi / Gulf / Levant accents before Egyptian.
     */
    private fun pickVoice(t: TextToSpeech) {
        val voices = runCatching { t.voices }.getOrNull() ?: return
        val accents = listOf("IQ", "SA", "XA", "AE", "KW", "JO", "EG")
        val best = voices.filter { v ->
            v.locale.language == "ar" &&
                v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true &&
                !v.isNetworkConnectionRequired
        }.sortedWith(
            compareByDescending<Voice> { it.quality }
                .thenBy { v -> accents.indexOf(v.locale.country).let { if (it < 0) accents.size else it } }
                .thenBy { it.latency }
        ).firstOrNull() ?: return
        runCatching { t.setVoice(best) }
    }

    /** Opens the voice-download page of the engine that knows Arabic (or of any engine). */
    fun installVoiceIntent(): Intent {
        val i = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        (missingEngine ?: engine)?.let { i.setPackage(it) }
        return i
    }

    /** Looks again (e.g. after the user downloaded the Arabic voice and came back). */
    fun recheck() {
        if (available || closed) return
        runCatching { tts?.shutdown() }
        tts = null
        others = null
        missingData = false
        missingEngine = null
        checked = false
        open(null)
    }

    fun say(text: String) {
        if (available) {
            runCatching { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav") }
        } else if (checked) {
            beep()
        }
    }

    /** Two short tones on the navigation sound channel. */
    private fun beep() {
        runCatching {
            val b = beeper ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85).also { beeper = it }
            b.startTone(ToneGenerator.TONE_PROP_BEEP2, 260)
        }
    }

    fun stop() {
        runCatching { tts?.stop() }
    }

    fun shutdown() {
        closed = true
        runCatching { tts?.shutdown() }
        tts = null
        runCatching { beeper?.release() }
        beeper = null
        available = false
    }
}
