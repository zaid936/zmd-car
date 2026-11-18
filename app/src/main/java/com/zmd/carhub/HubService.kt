package com.zmd.carhub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.UiModeManager
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock

/**
 * The always-running part of the hub. It watches for phones (USB cable and Bluetooth), keeps the
 * floating icons up to date, and opens the right app: CarPlay receiver for an iPhone, Android Auto
 * receiver for an Android phone, the chosen maps app otherwise.
 *
 * It only starts other apps; it never talks to the phone itself.
 */
class HubService : Service() {
    lateinit var prefs: Prefs
        private set
    private lateinit var overlay: Overlay
    /** Our side bar over the user's chosen maps app (Waze, Google Maps...). */
    private var railOverlay: RailOverlay? = null
    private val handler = Handler(Looper.getMainLooper())

    // What is physically connected right now. Keys: USB device name / Bluetooth address
    // (or "sim-..." for the test buttons in settings).
    private val usb = HashMap<String, PhoneType>()
    private val bt = HashMap<String, PhoneType>()
    private val pendingUnlink = HashMap<PhoneType, Runnable>()

    /** The phone type whose app the hub opened; the first one to connect keeps the screen. */
    var active: PhoneType? = null
        private set
    var splitOn = false
        private set
    private var openToken = 0

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val dev: UsbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) ?: return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> usbAttached(dev)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> usb.remove(dev.deviceName)?.let { scheduleUnlink(it) }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Perms.selfHeal(this)
        prefs = Prefs(this)
        overlay = Overlay(this)
        startForeground(1, buildNotification())
        // The apps that come inside ZMD Car (CarPlay, Android Auto) and aren't installed yet go to
        // Download/ZMD Car straight away, so they can be installed by hand from a file manager.
        // Each file is copied once; later starts see the same size and skip it.
        Thread({
            for (app in Bundled.needed(this)) {
                val f = Bundled.file(this, app) ?: continue
                PublicCopy.save(this, f, PublicCopy.apkName(this, f, f.name))
            }
        }, "zmd-bundled-copy").start()
        // Android Auto is built in: a separate ZMD Auto on the same screen must not answer the
        // phone too. It steps aside on this signal (only apps with our key may send it).
        if (Apps.isInstalled(this, Prefs.SEPARATE_AUTO)) {
            runCatching { sendBroadcast(Intent("com.zmd.auto.STEP_ASIDE").setPackage(Prefs.SEPARATE_AUTO)) }
        }
        syncAutoModes() // the built-in Android Auto: cable / wireless as chosen in ZMD Car
        // Cable detection. RECEIVER_EXPORTED: these are system broadcasts (required flag on Android 14).
        androidx.core.content.ContextCompat.registerReceiver(this, usbReceiver, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        // ZMD Auto's reports. Only an app holding our signature permission (ZMD Auto) can send them.
        androidx.core.content.ContextCompat.registerReceiver(
            this, autoReceiver, IntentFilter(ZmdAuto.ACTION_STATUS), ZmdAuto.PERMISSION, null,
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED
        )
        overlay.show()
        // First run (or a required permission is off): the map sends the user to the setup screen,
        // which comes up before this service exists, so its "step aside" call found no service.
        // Start with the bar hidden; the map shows it when it comes on screen (onMapShown).
        if (setupPending()) overlay.setBarShown(false)
        // A phone may already be plugged in when the car starts.
        handler.post {
            val manager = getSystemService(Context.USB_SERVICE) as UsbManager
            runCatching { manager.deviceList.values.forEach { usbAttached(it) } }
        }
        scheduleStoreCheck()
        schedulePermCheck()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        overlay.show() // the overlay permission may have been granted since the last start
        reviewPermissions()
        when (intent?.action) {
            ACTION_BOOT -> handler.postDelayed({ if (active == null) openMap() }, 1500)
            ACTION_MAP -> openMap()
            ACTION_REBUILD -> overlay.rebuild()
            ACTION_BT -> intent?.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)?.let { btEvent(it, true) }
            ACTION_SIM -> intent?.let { simulate(it) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(usbReceiver) }
        runCatching { unregisterReceiver(autoReceiver) }
        handler.removeCallbacksAndMessages(null)
        overlay.hide()
        railOverlay?.release()
        instance = null
        super.onDestroy()
    }

    /**
     * From the accessibility helper: [pkg] came on screen (null = one of our own screens; [ourMap]
     * = our map, which has its own side bar). The side bar floats over the chosen maps app only,
     * and not while the screen is split.
     */
    private val railSkip = setOf("com.android.launcher3", "com.android.settings", "com.android.systemui",
        "com.google.android.inputmethod.latin", "com.android.packageinstaller", "com.google.android.packageinstaller")

    fun onAppOnScreen(pkg: String?, ourMap: Boolean) {
        val map = prefs.mapPackage
        val want = when {
            pkg == null -> if (ourMap) false else railOverlay?.showing == true
            // Every normal app (the chosen map, YouTube...): not CarPlay / Android Auto, not the
            // system's own screens, not while split.
            else -> !splitOn && pkg !in railSkip && pkg != prefs.carplayPackage && pkg != prefs.autoPackage &&
                pkg !in MapRail.PROJECTION && packageManager.getLaunchIntentForPackage(pkg) != null &&
                RailOverlay.wanted(this, pkg, map)
        }
        if (want) {
            val r = railOverlay ?: RailOverlay(this).also { railOverlay = it }
            r.show()
        } else {
            railOverlay?.hide()
        }
    }

    // ---------------------------------------------------------------- what is connected

    /** Connected in a way the user allowed for this phone type (cable / wireless / both). */
    fun isConnected(t: PhoneType): Boolean {
        val mode = prefs.mode(t)
        val cable = usb.containsValue(t)
        val wireless = bt.containsValue(t)
        return (cable && mode != LinkMode.WIRELESS) || (wireless && mode != LinkMode.CABLE)
    }

    private val usbManager by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }

    /** An iPhone is on the cable right now (the accessibility helper checks this before allowing USB). */
    fun iphoneOnCable(): Boolean = usb.values.contains(PhoneType.IPHONE)

    private fun usbAttached(dev: UsbDevice) {
        val t = Detect.usbType(dev) ?: return
        usb[dev.deviceName] = t
        if (prefs.autoOpenReceiver && t == PhoneType.IPHONE) {
            iphoneAutoOpen(carPlayMode(dev))
            return
        }
        onLink(t, cable = true)
    }

    /**
     * The iPhone is on the cable in its CarPlay configuration: after CarPlay was allowed it comes
     * back offering a USB network link (CDC NCM interfaces). Read from the device's own USB
     * description, which every app can see.
     */
    private fun carPlayMode(dev: UsbDevice): Boolean = runCatching {
        (0 until dev.configurationCount).any { c ->
            val cfg = dev.getConfiguration(c)
            (0 until cfg.interfaceCount).any { i ->
                val itf = cfg.getInterface(i)
                itf.interfaceClass == UsbConstants.USB_CLASS_COMM && itf.interfaceSubclass == 0x0D
            }
        }
    }.getOrDefault(false)

    private var iphoneWaitToken = 0

    /**
     * Auto mode, iPhone on the cable (seen in the car's log, 2026-11-17). CarPlay (DiPlay) does the
     * connecting by itself in the background: it asks Android for USB access, the iPhone drops off
     * and comes back in its CarPlay configuration, DiPlay asks once more and links up. The driver
     * stays on the map the whole time (the user asked: «يراقب، ما يحوّل إلا يفتح الكاربلي»), and the
     * hub brings CarPlay to the screen only when it has had time to come up:
     *  - iPhone back in CarPlay mode and no USB prompt left on screen for a few seconds → switch;
     *  - first plug-in and nothing asks for USB within 5 s → CarPlay isn't connecting on its own
     *    (e.g. after a restart of the screen), so open it to start it;
     *  - a prompt was answered but the iPhone never came back in CarPlay mode → open it after 10 s.
     * The hub asks for no USB permission of its own. Every plug-in starts the wait again.
     */
    private fun iphoneAutoOpen(carplayMode: Boolean) {
        overlay.refresh()
        if (active == PhoneType.IPHONE) return // CarPlay is already the open session
        val token = ++iphoneWaitToken
        overlay.showSteps(listOf(
            Step("اتصل آيفون بالكيبل", Step.DONE),
            if (carplayMode) Step("CarPlay دا يرتبط بالخلفية، ابقى على الخريطة", Step.ACTIVE)
            else Step("إذا طلع طلب سماح USB، وافق عليه", Step.ACTIVE),
        ))
        val started = SystemClock.elapsedRealtime()
        var quietSince = started
        var promptSeen = false
        val check = object : Runnable {
            override fun run() {
                if (token != iphoneWaitToken || active == PhoneType.IPHONE) return
                if (!isConnected(PhoneType.IPHONE)) return
                val now = SystemClock.elapsedRealtime()
                // null = the accessibility helper is off: then we can't see prompts, so wait longer.
                val dialog = HubA11yService.systemDialogOpen()
                if (dialog == true) {
                    promptSeen = true
                    quietSince = now
                }
                val quiet = now - quietSince
                val waited = now - started
                val go = when {
                    waited > 90_000L -> true
                    carplayMode -> quiet >= (if (dialog == null) 7000L else 4500L)
                    promptSeen -> quiet >= 10_000L
                    else -> dialog != true && waited >= (if (dialog == null) 8000L else 5000L)
                }
                if (go) onLink(PhoneType.IPHONE, cable = true) else handler.postDelayed(this, 400)
            }
        }
        handler.postDelayed(check, 1200)
    }

    /** Called by [BtReceiver] for every Bluetooth connect and disconnect. */
    fun btEvent(dev: BluetoothDevice, connected: Boolean) {
        val mac = dev.address ?: return
        if (!connected) {
            if (mac == autoBtMac) autoBtMac = null
            bt.remove(mac)?.let { scheduleUnlink(it) }
            return
        }
        // The Android phone's Bluetooth address: wireless Android Auto calls exactly this phone.
        if (prefs.phoneType(mac) == PhoneType.ANDROID || Detect.btGuess(dev) == PhoneType.ANDROID) autoBtMac = mac
        if (!Detect.btLooksLikePhone(dev)) return
        val name = runCatching { dev.name }.getOrNull() ?: mac
        if (prefs.isKnown(mac)) {
            val t = prefs.phoneType(mac) ?: return // the user said "not a phone"
            bt[mac] = t
            onLink(t, cable = false)
            return
        }
        val guess = Detect.btGuess(dev)
        if (guess != null) {
            prefs.savePhone(mac, name, guess)
            bt[mac] = guess
            onLink(guess, cable = false)
            return
        }
        overlay.askPhoneType(name) { picked ->
            prefs.savePhone(mac, name, picked)
            if (picked != null) {
                if (picked == PhoneType.ANDROID) autoBtMac = mac
                bt[mac] = picked
                onLink(picked, cable = false)
            }
        }
    }

    private fun onLink(t: PhoneType, cable: Boolean) {
        pendingUnlink.remove(t)?.let { handler.removeCallbacks(it) }
        overlay.refresh()
        val mode = prefs.mode(t)
        val allowed = if (cable) mode != LinkMode.WIRELESS else mode != LinkMode.CABLE
        if (!allowed) return
        if (active == t) return // same phone re-announcing itself (a cable re-plug in the middle of a session)
        val what = "${phoneWord(t)} ${if (cable) "بالكيبل" else "لاسلكي"}"
        val current = active
        if (current != null && isConnected(current)) {
            overlay.showSteps(listOf(Step("اتصل $what. دوس أيقونته إذا تريد تحوّل", Step.DONE)))
            overlay.hideStepsLater(5000)
            return
        }
        // Default: don't grab the screen. Light the icon and let the user open it when ready,
        // so they can answer the USB permission prompt first. Auto-open is an opt-in setting.
        if (!prefs.autoOpenReceiver) {
            overlay.showSteps(listOf(Step("اتصل $what. دوس أيقونته حتى تفتح ${titleOf(t)}", Step.DONE)))
            overlay.hideStepsLater(7000)
            return
        }
        open(t, "انكشف $what", cable)
    }

    /**
     * A disconnect is only believed after a short wait: a phone on a cable briefly drops off and
     * comes back while its receiver app is setting the session up.
     */
    private fun scheduleUnlink(t: PhoneType) {
        pendingUnlink.remove(t)?.let { handler.removeCallbacks(it) }
        val run = Runnable {
            pendingUnlink.remove(t)
            onUnlink(t)
        }
        pendingUnlink[t] = run
        // An Android phone being switched into Android Auto mode leaves the cable for a few seconds
        // and comes back as a different device: that is not the phone going away.
        val switching = t == PhoneType.ANDROID && autoPhase == ZmdAuto.SWITCHING && autoBusy()
        handler.postDelayed(run, if (switching) 9000L else 2500L)
    }

    private fun onUnlink(t: PhoneType) {
        overlay.refresh()
        if (isConnected(t)) return // still connected the other way
        if (t == PhoneType.ANDROID) {
            autoWanted = false
            handler.removeCallbacks(autoLostCheck)
        }
        overlay.showSteps(listOf(Step("انفصل ${phoneWord(t)}", Step.DONE)))
        overlay.hideStepsLater(3000)
        if (active != t) return
        active = null
        val pkg = prefs.pkg(t)
        val other = PhoneType.values().firstOrNull { it != t && isConnected(it) }
        if (other != null) open(other, null)
        else if (prefs.backToMap) openMap()
        // Close the receiver (CarPlay / Android Auto) fully once the phone is gone: bring the map
        // forward first (so the receiver is in the background), then kill its background processes.
        handler.postDelayed({ closeApp(pkg) }, 1200)
    }

    /** Stops [pkg]'s background processes. Needs KILL_BACKGROUND_PROCESSES; only works once it's not foreground. */
    private fun closeApp(pkg: String) {
        if (active != null) return // a phone reconnected meanwhile
        if (pkg == packageName) return // the built-in Android Auto is part of us: never kill ourselves
        runCatching {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.killBackgroundProcesses(pkg)
        }
    }

    /** The user changed cable / wireless / both from the panel or settings. */
    fun onModeChanged() {
        overlay.refresh()
        syncAutoModes() // the built-in Android Auto follows the same choice
    }

    fun refreshOverlay() {
        overlay.refresh()
    }

    // ---------------------------------------------------------------- opening things

    fun onIconTap(t: PhoneType) {
        if (isConnected(t)) open(t, null) else overlay.showPanel(t)
    }

    /**
     * Opens the receiver app for [t] and shows the stages on screen. The stages are only what the
     * hub can see from outside: phone detected, app being opened, app on screen.
     */
    fun open(t: PhoneType, detected: String?, cable: Boolean? = null) {
        val token = ++openToken
        val pkg = prefs.pkg(t)
        val title = titleOf(t)
        if (t == PhoneType.ANDROID && pkg == ZmdAuto.PKG && Apps.isInstalled(this, pkg)) {
            openZmdAuto(detected)
            return
        }
        val done = ArrayList<Step>()
        if (detected != null) done += Step(detected, Step.DONE)
        if (!Apps.isInstalled(this, pkg)) {
            overlay.showSteps(done + Step("برنامج $title مو منصّب على الشاشة", Step.ERROR))
            overlay.hideStepsLater(6000)
            return
        }
        // A tap on the icon doesn't say how the phone is connected: work it out, so a phone that is
        // only on Bluetooth gets the WIRELESS way in (DiPlay's home screen, which connects by itself
        // with its «connect when DiPlay opens» option), not the cable way.
        val how = cable ?: when {
            usb.containsValue(t) && prefs.mode(t) != LinkMode.WIRELESS -> true
            bt.containsValue(t) && prefs.mode(t) != LinkMode.CABLE -> false
            else -> null
        }
        val way = when (how) { true -> " بالكيبل"; false -> " لاسلكي"; null -> "" }
        overlay.showSteps(done + Step("دا ينفتح $title$way", Step.ACTIVE))
        handler.postDelayed({
            if (token != openToken) return@postDelayed
            if (!Apps.launchProjection(this, pkg, how)) {
                overlay.showSteps(done + Step("ما انفتح $title", Step.ERROR))
                overlay.hideStepsLater(6000)
                return@postDelayed
            }
            active = t
            splitOn = false
            // CarPlay / Android Auto takes the whole screen: the bar steps aside. A swipe from
            // the right edge brings back the map and split tools.
            overlay.setBarShown(false)
            overlay.refresh()
            waitOnScreen(token, pkg, title, done, 0)
        }, 500)
    }

    private fun waitOnScreen(token: Int, pkg: String, title: String, done: List<Step>, tries: Int) {
        if (token != openToken) return
        // With the accessibility helper on we know which app is really on screen;
        // without it, assume the app is up after a moment.
        val helper = HubA11yService.instance != null
        val shown = if (helper) HubA11yService.foreground == pkg else tries >= 3
        when {
            shown -> {
                overlay.showSteps(done + Step("انفتح $title", Step.DONE))
                overlay.hideStepsLater(2500)
            }
            tries >= 16 -> {
                overlay.showSteps(done + Step("$title تأخر. دوس أيقونته مرة ثانية", Step.ERROR))
                overlay.hideStepsLater(6000)
            }
            else -> handler.postDelayed({ waitOnScreen(token, pkg, title, done, tries + 1) }, 500)
        }
    }

    // ---------------------------------------------------------------- ZMD Auto (Android Auto)

    /**
     * ZMD Auto's reports ([ZmdAuto]). It connects in the background while the driver stays on the
     * map, says how far it got (or exactly why it failed), and Android Auto comes on screen only
     * once the phone has accepted. When Android Auto ends, the driver lands back on our map.
     */
    private val autoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getStringExtra(ZmdAuto.EXTRA_STATE) ?: return
            onAutoStatus(
                state,
                intent.getStringExtra(ZmdAuto.EXTRA_REASON),
                intent.getStringExtra(ZmdAuto.EXTRA_DETAIL),
                intent.getBooleanExtra(ZmdAuto.EXTRA_SHOWN, false),
            )
        }
    }

    /** ZMD Auto's last reported state ("" = nothing yet) and when it came. */
    private var autoPhase = ""
    private var autoPhaseAt = 0L
    /** When the hub last asked ZMD Auto to connect (to notice if it never answers). */
    private var autoAskedAt = 0L
    /** The driver wants Android Auto on screen (auto mode, or its icon was tapped). */
    private var autoWanted = false
    /** The "phone detected" line shown in front of ZMD Auto's own stages. */
    private var autoDetected: Step? = null

    private val autoInProgress = setOf(
        ZmdAuto.START, ZmdAuto.WIRELESS, ZmdAuto.PERMISSION_ASK, ZmdAuto.SWITCHING, ZmdAuto.CONNECTING, ZmdAuto.HANDSHAKE
    )

    private fun showAutoStep(step: Step) = overlay.showSteps(listOfNotNull(autoDetected, step))

    /** The Android phone is on the cable (or just dropped off it, as it does while switching modes). */
    private fun androidOnCable(): Boolean =
        usb.containsValue(PhoneType.ANDROID) || pendingUnlink.containsKey(PhoneType.ANDROID)

    /** Bluetooth address of the Android phone connected over Bluetooth now (for wireless Android Auto). */
    private var autoBtMac: String? = null

    /** The Android phone is here over Bluetooth and the driver allows wireless Android Auto. */
    private fun androidWireless(): Boolean =
        autoBtMac != null && bt.containsValue(PhoneType.ANDROID) && prefs.mode(PhoneType.ANDROID) != LinkMode.CABLE

    /** When the driver last picked a phone by hand for wireless Android Auto ([connectAutoTo]). */
    private var autoManualAt = 0L

    /** The phone is reachable one way or the other (cable, wireless over Bluetooth, or picked by hand just now). */
    private fun androidPresent(): Boolean = androidOnCable() || androidWireless() ||
        SystemClock.elapsedRealtime() - autoManualAt < 60_000

    /** Phones paired with this screen over Bluetooth, as (address, name); null = no Bluetooth permission. */
    fun pairedPhones(): List<Pair<String, String>>? {
        if (android.os.Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Perms.BT_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        val adapter = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter ?: return emptyList()
        return runCatching {
            adapter.bondedDevices
                .filter { Detect.btLooksLikePhone(it) && prefs.phoneType(it.address) != PhoneType.IPHONE && Detect.btGuess(it) != PhoneType.IPHONE }
                .map { it.address to (runCatching { it.name }.getOrNull() ?: it.address) }
                .sortedByDescending { it.first == autoBtMac || prefs.phoneType(it.first) == PhoneType.ANDROID }
        }.getOrDefault(emptyList())
    }

    /** The driver picked this phone in the Android Auto panel: connect to it wirelessly now. */
    fun connectAutoTo(mac: String, name: String) {
        prefs.savePhone(mac, name, PhoneType.ANDROID)
        autoBtMac = mac
        autoManualAt = SystemClock.elapsedRealtime()
        autoDetected = Step("اخترت $name", Step.DONE)
        autoWanted = true
        if (autoPhase == ZmdAuto.READY || autoPhase == ZmdAuto.RUNNING) {
            askZmdAuto() // already up behind the map: bring it back
            return
        }
        showAutoStep(Step(ZmdAuto.stageText(ZmdAuto.WIRELESS)!!, Step.ACTIVE))
        autoAskToken++
        if (!ZmdAuto.connectWireless(this, mac)) {
            showAutoStep(Step("ما گدرت أشغّل Android Auto اللاسلكي", Step.ERROR))
            overlay.hideStepsLater(8000)
        }
    }

    /** ZMD Auto is part-way through connecting, and said so recently (wireless takes longer). */
    private fun autoBusy(): Boolean = autoPhase in autoInProgress &&
        SystemClock.elapsedRealtime() - autoPhaseAt < (if (autoPhase == ZmdAuto.WIRELESS) 50_000 else 15_000)

    /** The engine follows ZMD Car's cable / wireless choice for Android. */
    private fun syncAutoModes() {
        val m = prefs.mode(PhoneType.ANDROID)
        val usb = m != LinkMode.WIRELESS
        val wifi = m != LinkMode.CABLE
        // Only when it changed: each send wakes the engine's process.
        val key = "$usb/$wifi"
        if (hubPrefs().getString(KEY_AUTO_MODES, null) == key) return
        ZmdAuto.setModes(this, usb, wifi)
        hubPrefs().edit().putString(KEY_AUTO_MODES, key).apply()
    }

    private fun openZmdAuto(detected: String?) {
        if (detected != null) autoDetected = Step(detected, Step.DONE)
        if (!androidOnCable()) {
            val mac = autoBtMac
            if (mac != null && androidWireless()) {
                // Bluetooth only, wireless allowed: the engine calls this phone over Wi-Fi by itself.
                autoWanted = true
                if (autoPhase == ZmdAuto.READY || autoPhase == ZmdAuto.RUNNING) {
                    askZmdAuto() // already up behind the map: bring it back
                    return
                }
                if (autoBusy()) {
                    ZmdAuto.stageText(autoPhase)?.let { showAutoStep(Step(it, Step.ACTIVE)) }
                    return
                }
                showAutoStep(Step(ZmdAuto.stageText(ZmdAuto.WIRELESS)!!, Step.ACTIVE))
                autoAskToken++
                if (!ZmdAuto.connectWireless(this, mac)) {
                    showAutoStep(Step("ما گدرت أشغّل Android Auto اللاسلكي", Step.ERROR))
                    overlay.hideStepsLater(8000)
                }
                return
            }
            val why = if (prefs.mode(PhoneType.ANDROID) == LinkMode.CABLE)
                "Android Auto مضبوط على الكيبل: شبّك التلفون بالكيبل، أو غيّر «طريقة الاتصال» للاسلكي"
            else "شبّك التلفون بالكيبل، أو شغّل البلوتوث بالتلفون حتى يتصل لاسلكي"
            overlay.showSteps(listOfNotNull(autoDetected, Step(why, Step.DONE)))
            overlay.hideStepsLater(6000)
            return
        }
        autoWanted = true
        if (autoPhase == ZmdAuto.READY || autoPhase == ZmdAuto.RUNNING) {
            // Android Auto runs behind the map: ZMD Auto brings its screen back and answers RUNNING.
            askZmdAuto()
            return
        }
        // The phone re-appears on the cable in the middle of connecting (it does when it switches
        // into Android Auto mode): ZMD Auto is already on it, so don't ask again.
        if (detected != null && autoBusy()) {
            ZmdAuto.stageText(autoPhase)?.let { showAutoStep(Step(it, Step.ACTIVE)) }
            return
        }
        showAutoStep(Step("دا يرتبط Android Auto بالخلفية، ابقى على الخريطة", Step.ACTIVE))
        if (detected == null) askZmdAuto() else askZmdAutoWhenQuiet()
    }

    private var autoAskToken = 0

    /**
     * On plug-in, Android itself may first ask «open ZMD Auto for this phone?». Don't stack a
     * second prompt on top of it (stacked prompts are how CarPlay's auto mode got stuck before):
     * wait until no system prompt has been on screen for a moment. Once that prompt is answered,
     * ZMD Auto usually starts by itself and reports it, and then there is nothing left to ask.
     */
    private fun askZmdAutoWhenQuiet() {
        val token = ++autoAskToken
        val started = SystemClock.elapsedRealtime()
        handler.post(object : Runnable {
            var quietSince = 0L
            var promptShown = false
            override fun run() {
                if (token != autoAskToken || !autoWanted) return
                if (autoBusy() || autoPhase == ZmdAuto.READY || autoPhase == ZmdAuto.RUNNING) return // on it already
                val now = SystemClock.elapsedRealtime()
                // null = the accessibility helper is off and prompts can't be seen: just go on.
                if (HubA11yService.systemDialogOpen() == true) {
                    quietSince = 0L
                    if (!promptShown) {
                        promptShown = true
                        showAutoStep(Step("طلع سؤال USB على الشاشة: دوس «موافق» (وعلّم «دائماً» إذا موجودة)", Step.ACTIVE))
                    }
                } else if (quietSince == 0L) {
                    quietSince = now
                }
                val quiet = quietSince != 0L && now - quietSince >= 1200
                if (quiet || now - started > 40_000) askZmdAuto() else handler.postDelayed(this, 300)
            }
        })
    }

    /**
     * ZMD Auto answers at once: START (connecting now), or RUNNING if Android Auto was already up
     * behind the map (then its screen comes back by itself).
     */
    private fun askZmdAuto() {
        autoAskToken++
        if (!ZmdAuto.connect(this)) {
            showAutoStep(Step("ما گدرت أشغّل ZMD Auto (برنامج Android Auto)", Step.ERROR))
            overlay.hideStepsLater(8000)
            return
        }
        val asked = SystemClock.elapsedRealtime()
        autoAskedAt = asked
        handler.postDelayed({
            if (autoAskedAt == asked && autoPhaseAt < asked) {
                showAutoStep(Step("ZMD Auto ما رد على البرنامج. حدّث البرنامجين من المتجر، أو شيل الكيبل ورجّعه", Step.ERROR))
                overlay.hideStepsLater(10_000)
            }
        }, 6000)
    }

    private fun onAutoStatus(state: String, reason: String?, detail: String?, shown: Boolean) {
        autoPhase = state
        autoPhaseAt = SystemClock.elapsedRealtime()
        if (prefs.pkg(PhoneType.ANDROID) != ZmdAuto.PKG) return
        // "On the cable" here means the phone is reachable: by cable, or wirelessly over Bluetooth.
        val onCable = androidPresent()
        when (state) {
            ZmdAuto.READY -> {
                handler.removeCallbacks(autoLostCheck)
                // CarPlay already has the screen and nobody asked for Android Auto: don't take it.
                val carPlayBusy = active == PhoneType.IPHONE && isConnected(PhoneType.IPHONE)
                // active == ANDROID: the session dropped and came back while the driver was in it.
                val wanted = autoWanted || prefs.autoOpenReceiver || active == PhoneType.ANDROID
                if (shown || (wanted && !carPlayBusy)) {
                    if (!shown && !ZmdAuto.show(this)) ZmdAuto.connect(this)
                    autoOnScreen()
                } else {
                    showAutoStep(Step("Android Auto جاهز. دوس أيقونته حتى تفتحه", Step.DONE))
                    overlay.hideStepsLater(8000)
                }
            }
            ZmdAuto.RUNNING -> {
                handler.removeCallbacks(autoLostCheck)
                hubPrefs().edit().putBoolean(KEY_AUTO_RAN, true).apply()
                // Android Auto only runs once its screen exists, so it is on screen now (also the
                // answer when its icon was tapped while it ran behind the map: its screen is back).
                if (active != PhoneType.ANDROID || autoWanted) autoOnScreen()
            }
            ZmdAuto.ENDED -> {
                autoWanted = false
                if (reason == ZmdAuto.LOST) {
                    // Cable pulled: onUnlink() takes the driver back to the map. Still plugged in:
                    // ZMD Auto tries to get the session back for a few seconds first.
                    if (onCable) {
                        showAutoStep(Step("انقطع الاتصال ويا التلفون، دا نحاول نرجّعه", Step.ACTIVE))
                        handler.removeCallbacks(autoLostCheck)
                        handler.postDelayed(autoLostCheck, 10_000)
                    }
                } else {
                    autoDetected = null
                    showAutoStep(Step(if (reason == ZmdAuto.USER_EXIT) "سكّرت Android Auto" else "التلفون سكّر Android Auto", Step.DONE))
                    overlay.hideStepsLater(3000)
                    leaveAuto()
                }
            }
            ZmdAuto.FAILED -> {
                if (!onCable) return // the cable is out: onUnlink() already says the phone is gone
                // ZMD Auto often retries a failed try about 1.5 s later: show the failure only if
                // nothing newer has arrived by then.
                val at = autoPhaseAt
                handler.postDelayed({
                    if (autoPhaseAt != at) return@postDelayed
                    autoWanted = false
                    showAutoStep(Step(ZmdAuto.failText(reason, detail), Step.ERROR))
                    overlay.hideStepsLater(15_000)
                    if (active == PhoneType.ANDROID) leaveAuto()
                }, 2200)
            }
            else -> {
                // A stage on the way (looking for the phone, USB prompt, switching mode, linking).
                val text = ZmdAuto.stageText(state) ?: return
                if (onCable) showAutoStep(Step(text, Step.ACTIVE))
            }
        }
    }

    /** Android Auto is on screen: the bar steps aside (a swipe from the edge brings the tools). */
    private fun autoOnScreen() {
        openToken++
        active = PhoneType.ANDROID
        splitOn = false
        overlay.setBarShown(false)
        overlay.refresh()
        showAutoStep(Step("انفتح Android Auto", Step.DONE))
        overlay.hideStepsLater(1500)
        autoDetected = null
        autoWanted = false // done; the next tap on its icon asks again
    }

    /** Android Auto is over: straight back to our map (the user asked for exactly that). */
    private fun leaveAuto() {
        handler.removeCallbacks(autoLostCheck)
        if (active != PhoneType.ANDROID) return
        active = null
        openMap()
    }

    /** The session broke with the cable still in: give ZMD Auto time to win it back, then give up. */
    private val autoLostCheck = object : Runnable {
        override fun run() {
            if (autoPhase == ZmdAuto.READY || autoPhase == ZmdAuto.RUNNING) return
            if (autoBusy()) {
                handler.postDelayed(this, 5000) // still reconnecting
                return
            }
            if (autoPhase != ZmdAuto.FAILED) {
                showAutoStep(Step("ما رجع الاتصال ويا التلفون. شيل الكيبل ورجّعه", Step.ERROR))
                overlay.hideStepsLater(10_000)
            }
            leaveAuto()
        }
    }

    /**
     * The microphone notice: after Android Auto has run once, if ZMD Auto may not use the mic, the
     * Google Assistant inside Android Auto can't hear. true = the notice is up.
     */
    private fun autoMicNotice(): Boolean {
        if (active != null || prefs.pkg(PhoneType.ANDROID) != ZmdAuto.PKG) return false
        if (!hubPrefs().getBoolean(KEY_AUTO_RAN, false) || !ZmdAuto.isInstalled(this)) return false
        if (ZmdAuto.micAllowed(this)) return false
        if (SystemClock.elapsedRealtime() < permSnoozeUntil) return true // snoozed: leave it as it is
        overlay.showPermissionAlert("مايك Android Auto مطفّي", "حتى تشتغل الأوامر الصوتية (Google) داخل Android Auto") {
            ZmdAuto.askMic(this)
        }
        return true
    }

    private fun hubPrefs() = getSharedPreferences("hub", Context.MODE_PRIVATE)

    /** The built-in map ([MapActivity]) unless the user picked another maps app in settings. */
    fun openMap() {
        openToken++
        val pkg = prefs.mapPackage
        if (pkg != null && Apps.launch(this, pkg)) {
            overlay.setBarShown(true)
            return
        }
        launchOwnMap()
    }

    private fun launchOwnMap(adjacent: Boolean = false) {
        val i = Intent(this, MapActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (adjacent) i.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        runCatching { startActivity(i) }
    }

    /** The hub's own sheets (store, app picker) would sit under the bar; it steps aside meanwhile. */
    fun hideBarForSheet() {
        overlay.setBarShown(false)
    }

    /** Called by [MapActivity] whenever the map comes on screen. */
    fun onMapShown() {
        overlay.show()
        overlay.setBarShown(true)
        overlay.refresh()
        reviewPermissions()
    }

    fun openSettings() {
        runCatching {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** The map-app picker (only map apps), opened by long-pressing the map icon in the bar. */
    fun openMapPicker() {
        runCatching {
            startActivity(
                Intent(this, AppPickerActivity::class.java)
                    .putExtra(AppPickerActivity.TARGET, AppPickerActivity.MAP)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun openStore() {
        runCatching {
            startActivity(Intent(this, StoreActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // ---------------------------------------------------------------- store update badge

    /** Checks the store for waiting updates on a background thread and shows a dot on the store icon. */
    private val storeCheck = object : Runnable {
        override fun run() {
            Thread {
                val n = runCatching { StoreActivity.countUpdates(this@HubService) }.getOrDefault(0)
                handler.post { overlay.setStoreBadge(n > 0) }
            }.start()
            handler.postDelayed(this, 30 * 60 * 1000L) // re-check every 30 minutes
        }
    }

    private fun scheduleStoreCheck() {
        handler.removeCallbacks(storeCheck)
        handler.postDelayed(storeCheck, 5000)
    }

    // ---------------------------------------------------------------- permission monitoring

    private var permSnoozeUntil = 0L
    private val permCheck = object : Runnable {
        override fun run() {
            reviewPermissions()
            handler.postDelayed(this, 180_000L) // re-check every 3 minutes
        }
    }

    private fun schedulePermCheck() {
        handler.removeCallbacks(permCheck)
        handler.postDelayed(permCheck, 3000)
    }

    /** Same rule as MapActivity uses to open the setup screen instead of the map. */
    private fun setupPending(): Boolean = !prefs.setupDone || Perms.missingRequired(this).isNotEmpty()

    /** If a permission is off, warn with a floating card plus a button straight to its settings page. */
    fun reviewPermissions() {
        // Launcher and resizable windows are offered on the setup screen only, never nagged about.
        val missing = Perms.missing(this).filter { it != Perm.LAUNCHER } // required first
        if (missing.isEmpty()) {
            if (!autoMicNotice()) overlay.hidePermissionAlert()
            return
        }
        if (Perm.OVERLAY in missing) {
            // Can't float a warning without the overlay permission; the setup screen covers that one.
            overlay.hidePermissionAlert()
            return
        }
        if (SystemClock.elapsedRealtime() < permSnoozeUntil) return
        val p = missing.first()
        overlay.showPermissionAlert("${p.title} مطفّية", p.why) {
            // LOCATION's real runtime dialog can only come from an activity, so go through setup.
            if (p == Perm.LOCATION) runCatching { startActivity(Perms.setupIntent(this)) }
            else Perms.open(this, p)
        }
    }

    /** "لاحقاً" on the warning card: stop nagging for a few minutes. */
    fun snoozePermAlert() {
        permSnoozeUntil = SystemClock.elapsedRealtime() + 300_000L
        overlay.hidePermissionAlert()
    }

    /**
     * Two apps side by side. From the bar: the map plus the chosen second app. From the edge tools
     * inside CarPlay / Android Auto ([withCurrent]): that app stays on the driver's side and the
     * second app opens next to it.
     *
     * The modern way (Android 12L / API 32+) is to launch the second app with
     * FLAG_ACTIVITY_LAUNCH_ADJACENT, which enters split from a full-screen app on its own — no
     * accessibility action needed. On older head units that don't honour it we fall back to the
     * system's split toggle (GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN), which needs the accessibility helper.
     */
    fun toggleSplit(withCurrent: Boolean = false) {
        if (splitOn) {
            exitSplit()
            return
        }
        // Remember which app is on screen NOW, before the app picker covers it.
        splitSnapshot = if (withCurrent) HubA11yService.topAppPackage() else null
        splitAskedAt = android.os.SystemClock.elapsedRealtime()
        // Always let the user choose the second app first, then split (the user's request):
        // show every installed app, and the picker calls startChosenSplit() once they pick.
        runCatching {
            startActivity(
                Intent(this, AppPickerActivity::class.java)
                    .putExtra(AppPickerActivity.TARGET, AppPickerActivity.SPLIT)
                    .putExtra(AppPickerActivity.START_AFTER, true)
                    .putExtra(AppPickerActivity.WITH_CURRENT, withCurrent)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** A split is being set up (picker open or apps moving): the home screen must not jump to the map. */
    fun splitBusy(): Boolean = splitPending || android.os.SystemClock.elapsedRealtime() - splitAskedAt < 4000

    private var splitAskedAt = 0L

    /** Called back from [AppPickerActivity] after the user chose the second app for the split. */
    fun startChosenSplit(withCurrent: Boolean) {
        val second = prefs.splitPackage ?: return
        splitAskedAt = android.os.SystemClock.elapsedRealtime()
        enterSplit(second, withCurrent)
    }

    /**
     * CarPlay / Android Auto receivers put the system into "car mode", and Android disables
     * split-screen while car mode is on. So a split can only start when no receiver is driving.
     */
    private fun inCarMode(): Boolean {
        val ui = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager ?: return false
        return ui.currentModeType == Configuration.UI_MODE_TYPE_CAR
    }

    private var splitPending = false
    /** The app on screen when split was pressed (taken before the app picker opens). */
    private var splitSnapshot: String? = null
    /** The current split is two free-floating windows (screens without system split), not a real split. */
    private var splitFreeform = false
    private var splitBase: String? = null
    private var splitSecond: String? = null

    private val splitTimeout = Runnable {
        if (!splitPending) return@Runnable
        splitPending = false
        HubA11yService.cancel()
        splitOn = HubA11yService.splitActive() == true
        overlay.refresh()
        if (!splitOn) {
            val base = splitBase
            val second = splitSecond
            if (base != null && second != null && freeformAvailable()) {
                // The system split didn't take: fall back to two windows side by side.
                freeformSplit(base, second)
                return@Runnable
            }
            overlay.showSteps(listOf(Step("ما انقسمت الشاشة. بعض البرامج ما تقبل التقسيم، جرّب برنامج ثاني", Step.ERROR)))
            overlay.hideStepsLater(6000)
        }
    }

    /**
     * [withCurrent] (the bar and the edge tools both use it): the app on screen now keeps the
     * driver's half and the chosen app opens next to it, as the user asked, e.g. YouTube + any
     * app. Only when the hub's map is on screen (or nothing else is known) is the map that half.
     */
    private fun enterSplit(second: String, withCurrent: Boolean) {
        railOverlay?.hide()
        // Car mode (set by a receiver like DiAuto) normally disables split. Try to leave it and
        // attempt the split anyway instead of blocking — on some head units it still works.
        if (inCarMode()) {
            runCatching { (getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).disableCarMode(0) }
        }
        if (!Apps.isInstalled(this, second)) {
            overlay.showSteps(listOf(Step("البرنامج المختار مو منصّب", Step.ERROR)))
            overlay.hideStepsLater(5000)
            return
        }
        // The app that was on screen when split was pressed: the live snapshot first, else what the
        // helper last saw. Our own package there means the map was on screen.
        val snap = splitSnapshot
        splitSnapshot = null
        val current: String? = when {
            snap != null -> snap.takeIf { it != packageName }
            HubA11yService.mapOnTop -> null
            else -> HubA11yService.foreground
        }
        val useCurrent = withCurrent && current != null
        val base: String = if (useCurrent) current!! else openMapForSplit()
        splitBase = base
        splitSecond = second

        if (!multiWindowSupported()) {
            if (freeformAvailable()) {
                freeformSplit(base, second)
            } else {
                overlay.showSteps(listOf(Step(
                    "هالشاشة ما تدعم تقسيم الشاشة. شغّل «النوافذ الحرة» (freeform) من خيارات المطوّر وارجع جرّب",
                    Step.ERROR
                )))
                overlay.hideStepsLater(9000)
            }
            return
        }
        if (HubA11yService.instance == null) {
            if (!Perms.hasA11yService(this) && android.os.Build.VERSION.SDK_INT >= 32) {
                // The hand-install build has no helper (Play Protect blocks APKs with one), so it
                // can't watch the screen: just open the chosen app next to the one on screen.
                blindSplit(second)
                return
            }
            overlay.showSteps(listOf(Step("التقسيم يحتاج تفعيل «إمكانية الوصول» لـ ZMD Car من الإعدادات", Step.ERROR)))
            overlay.hideStepsLater(7000)
            return
        }
        overlay.showSteps(listOf(Step("دا نقسم الشاشة", Step.ACTIVE)))
        // The accessibility service runs the proven sequence: wait for the base app on screen, fire
        // the system split action, wait for the split UI, then open the second app into the free half.
        splitPending = true
        HubA11yService.startSplit(base, second)
        handler.removeCallbacks(splitTimeout)
        handler.postDelayed(splitTimeout, 6000)
    }

    /**
     * Split without the accessibility helper (the build installed by hand): the same first move the
     * helper makes (Android 12L+ opens an app flagged LAUNCH_ADJACENT in the other half), only
     * without checking that the split really happened. If the screen ignores it, the chosen app
     * opens full screen and the bar's split button brings the first app back.
     */
    private fun blindSplit(second: String) {
        overlay.showSteps(listOf(Step("دا نقسم الشاشة", Step.ACTIVE)))
        // Give the first app (just opened, or uncovered by the app picker) a moment on screen.
        handler.postDelayed({
            val i = if (second == packageName) Intent(this, MapActivity::class.java)
            else packageManager.getLaunchIntentForPackage(second)
            val ok = i != null && runCatching {
                startActivity(i.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                ))
            }.isSuccess
            splitOn = ok
            overlay.refresh()
            overlay.showSteps(listOf(
                if (ok) Step("انفتح البرنامج جنبه. إذا ما انقسمت الشاشة، التقسيم يحتاج نسخة ZMD Car الكاملة", Step.DONE)
                else Step("ما انقسمت الشاشة. جرّب برنامج ثاني", Step.ERROR)
            ))
            overlay.hideStepsLater(6000)
        }, 1200)
    }

    /** Brings the map (the chosen maps app, or ours) to the front and returns its package. */
    private fun openMapForSplit(): String {
        val map = prefs.mapPackage
        return if (map != null && Apps.isInstalled(this, map)) {
            Apps.launch(this, map)
            map
        } else {
            launchOwnMap()
            packageName // the built-in MapActivity
        }
    }

    /** The screen supports split-screen (Android turns it off on some head units and low-memory devices). */
    private fun multiWindowSupported(): Boolean {
        val sys = android.content.res.Resources.getSystem()
        fun flag(name: String): Boolean? {
            val id = sys.getIdentifier(name, "bool", "android")
            return if (id == 0) null else runCatching { sys.getBoolean(id) }.getOrNull()
        }
        if (flag("config_supportsMultiWindow") == false) return false
        if (flag("config_supportsSplitScreenMultiWindow") == false) return false
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        return am?.isLowRamDevice != true
    }

    /** Free-floating windows are available (a screen feature, or switched on in developer options). */
    private fun freeformAvailable(): Boolean =
        packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT) ||
            runCatching { android.provider.Settings.Global.getInt(contentResolver, "enable_freeform_support", 0) != 0 }
                .getOrDefault(false)

    /**
     * Split for screens without system split: both apps as windows, the main one on the left (the
     * driver's side; Iraq drives on the right) and the chosen one on the right.
     */
    private fun freeformSplit(base: String, second: String) {
        val dm = resources.displayMetrics
        val half = dm.widthPixels / 2
        launchInBounds(base, android.graphics.Rect(0, 0, half, dm.heightPixels))
        handler.postDelayed({ launchInBounds(second, android.graphics.Rect(half, 0, dm.widthPixels, dm.heightPixels)) }, 600)
        splitFreeform = true
        splitOn = true
        overlay.refresh()
        overlay.showSteps(listOf(Step("انقسمت الشاشة (نوافذ)", Step.DONE)))
        overlay.hideStepsLater(2500)
    }

    private fun launchInBounds(pkg: String, bounds: android.graphics.Rect): Boolean {
        val i = if (pkg == packageName) Intent(this, MapActivity::class.java)
        else packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        return runCatching {
            startActivity(i, android.app.ActivityOptions.makeBasic().setLaunchBounds(bounds).toBundle())
            true
        }.getOrDefault(false)
    }

    /** Called by [HubA11yService] once it has placed the second app in the split. */
    fun onSplitSettled() {
        handler.removeCallbacks(splitTimeout)
        if (!splitPending) return
        splitPending = false
        splitOn = HubA11yService.splitActive() != false
        overlay.refresh()
        if (splitOn) {
            overlay.showSteps(listOf(Step("انقسمت الشاشة", Step.DONE)))
            overlay.hideStepsLater(2500)
        } else {
            overlay.showSteps(listOf(Step("ما انقسمت الشاشة. جرّب برنامج ثاني", Step.ERROR)))
            overlay.hideStepsLater(6000)
        }
    }

    private fun exitSplit() {
        if (splitFreeform) {
            // Two windows: put the main one back over the whole screen.
            val dm = resources.displayMetrics
            val base = splitBase
            if (base == null || !launchInBounds(base, android.graphics.Rect(0, 0, dm.widthPixels, dm.heightPixels))) openMap()
            splitFreeform = false
        } else if (!HubA11yService.toggleSplit()) {
            // Prefer the system toggle; otherwise bring the driver-side app back full screen.
            val base = splitBase
            if (base == null || base == packageName || !Apps.launch(this, base)) {
                val map = prefs.mapPackage
                if (map == null || !Apps.launch(this, map)) launchOwnMap()
            }
        }
        splitOn = false
        overlay.refresh()
    }

    // ---------------------------------------------------------------- test buttons

    /** Pretend a phone connected or disconnected, so the flow can be tried without a phone. */
    private fun simulate(i: Intent) {
        if (i.getBooleanExtra(EXTRA_CLEAR, false)) {
            val gone = (usb.filterKeys { it.startsWith(SIM) }.values + bt.filterKeys { it.startsWith(SIM) }.values).toSet()
            usb.keys.removeAll { it.startsWith(SIM) }
            bt.keys.removeAll { it.startsWith(SIM) }
            gone.forEach { onUnlink(it) }
            overlay.refresh()
            return
        }
        val t = runCatching { PhoneType.valueOf(i.getStringExtra(EXTRA_TYPE) ?: "") }.getOrNull() ?: return
        val cable = i.getBooleanExtra(EXTRA_CABLE, true)
        if (cable) usb[SIM + t.name] = t else bt[SIM + t.name] = t
        onLink(t, cable)
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.app_name), NotificationManager.IMPORTANCE_MIN))
        val tap = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_map)
            .setContentTitle("${getString(R.string.app_name)} شغال")
            .setContentText("يراقب اتصال التلفون")
            .setContentIntent(tap)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_BOOT = "com.zmd.carhub.BOOT"
        const val ACTION_MAP = "com.zmd.carhub.MAP"
        const val ACTION_REBUILD = "com.zmd.carhub.REBUILD"
        const val ACTION_BT = "com.zmd.carhub.BT"
        const val ACTION_SIM = "com.zmd.carhub.SIM"
        const val EXTRA_TYPE = "type"
        const val EXTRA_CABLE = "cable"
        const val EXTRA_CLEAR = "clear"
        private const val SIM = "sim-"
        private const val CHANNEL = "hub"
        /** SharedPreferences "hub": Android Auto (ZMD Auto) has run at least once on this screen. */
        private const val KEY_AUTO_RAN = "zmd_auto_ran"
        /** Cable / wireless last sent to the built-in Android Auto ("usb/wifi"). */
        private const val KEY_AUTO_MODES = "zmd_auto_modes"

        @Volatile
        var instance: HubService? = null
            private set

        fun titleOf(t: PhoneType): String = if (t == PhoneType.IPHONE) "CarPlay" else "Android Auto"

        fun phoneWord(t: PhoneType): String = if (t == PhoneType.IPHONE) "آيفون" else "أندرويد"

        fun start(ctx: Context, action: String? = null, extras: Intent.() -> Unit = {}) {
            val i = Intent(ctx, HubService::class.java)
            if (action != null) i.action = action
            i.extras()
            runCatching { ctx.startForegroundService(i) }
        }
    }
}
