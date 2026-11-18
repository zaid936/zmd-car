package com.zmd.carhub

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL

/** One file in the owner's store list. */
class StoreItem(
    val name: String,
    val url: String,
    val version: String,
    val versionCode: Long,
    val note: String,
    val sha256: String,
    val pkg: String,
    val size: Long = 0,
    /** SHA-256 of the signing certificate (lowercase hex), from the store's index.json. */
    val signer: String = "",
    /** URL of the app's own icon (uploaded by the store), shown before it is installed. */
    val icon: String = "",
) {
    /** Local file name: last part of the link, with anything unusual replaced. */
    val fileName: String
        get() {
            val last = runCatching { URL(url).path.substringAfterLast('/') }.getOrDefault("")
            val clean = last.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_')
            return clean.ifEmpty { "file_" + url.hashCode().toUInt() }
        }
    val isApk: Boolean get() = fileName.endsWith(".apk", ignoreCase = true)
}

/**
 * The private store. Reads the owner's index.json (link set in settings), lists the files,
 * downloads them into the app's own folder and hands APKs to Android's installer, which asks
 * the user to confirm every install. Nothing is installed silently.
 *
 * index.json:
 * { "items": [ { "name": "...", "url": "app.apk" or "https://...", "version": "1.2",
 *               "versionCode": 12, "package": "com.x", "sha256": "...", "note": "..." } ] }
 * A relative "url" is read relative to the index.json link.
 */
class StoreActivity : Activity() {
    companion object {
        /** Appends a changing value so a CDN can't hand back a stale store list. */
        fun fresh(url: String): String =
            url + (if (url.contains('?')) "&" else "?") + "t=" + System.currentTimeMillis()

        /**
         * How many apps in the store are newer than what is installed on this screen. Network call:
         * run it off the main thread. Returns 0 when there is no store link or the store can't be reached.
         */
        fun countUpdates(ctx: android.content.Context): Int {
            val url = Prefs(ctx).storeUrl.trim()
            if (url.isEmpty()) return 0
            val items = runCatching { parseIndex(url, Net.getText(fresh(url))) }.getOrNull() ?: return 0
            return items.count { stateOf(ctx, it) == State.UPDATE }
        }

        enum class State { NONE, LATEST, UPDATE, INSTALLED, OTHER_SIGNER }

        /**
         * Compares a store item with what is installed on this screen. OTHER_SIGNER: the store has a
         * newer version, but signed with a different key than the installed app, so Android would
         * refuse to install it over the old one.
         */
        fun stateOf(ctx: android.content.Context, item: StoreItem): State {
            if (item.pkg.isEmpty()) return State.NONE
            val info = runCatching { ctx.packageManager.getPackageInfo(item.pkg, 0) }.getOrNull() ?: return State.NONE
            if (item.versionCode <= 0) return State.INSTALLED
            if (PackageInfoCompat.getLongVersionCode(info) >= item.versionCode) return State.LATEST
            if (item.signer.isNotEmpty()) {
                val have = installedSigners(ctx, item.pkg)
                if (have.isNotEmpty() && item.signer !in have) return State.OTHER_SIGNER
            }
            return State.UPDATE
        }

        /** SHA-256 fingerprints (lowercase hex) of the installed app's signing certificates. */
        @Suppress("DEPRECATION")
        fun installedSigners(ctx: android.content.Context, pkg: String): Set<String> = runCatching {
            val pm = ctx.packageManager
            val certs: List<ByteArray> = if (android.os.Build.VERSION.SDK_INT >= 28) {
                val si = pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                    ?: return@runCatching emptySet<String>()
                val list = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
                list.orEmpty().map { it.toByteArray() }
            } else {
                pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_SIGNATURES).signatures.orEmpty().map { it.toByteArray() }
            }
            val md = java.security.MessageDigest.getInstance("SHA-256")
            certs.map { c -> md.digest(c).joinToString("") { "%02x".format(it) } }.toSet()
        }.getOrDefault(emptySet())

        fun parseIndex(base: String, text: String): List<StoreItem> {
            val t = text.trim()
            val arr: JSONArray = if (t.startsWith("[")) JSONArray(t) else JSONObject(t).optJSONArray("items") ?: JSONArray()
            val out = ArrayList<StoreItem>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val raw = o.optString("url", o.optString("file", "")).trim()
                if (raw.isEmpty()) continue
                val abs = runCatching { URL(URL(base), raw).toString() }.getOrNull() ?: continue
                if (!Net.allowed(abs)) continue
                // The public DiAuto is replaced by ZMD Auto (bundled in the hub): never offer it.
                if (o.optString("package", "").trim() == Prefs.OLD_AUTO) continue
                out += StoreItem(
                    name = o.optString("name", raw.substringAfterLast('/')),
                    url = abs,
                    version = o.optString("version", ""),
                    versionCode = o.optLong("versionCode", 0),
                    note = o.optString("note", ""),
                    sha256 = o.optString("sha256", "").lowercase().trim(),
                    pkg = o.optString("package", "").trim(),
                    size = o.optLong("size", 0),
                    signer = o.optString("signer", "").lowercase().trim(),
                    icon = o.optString("icon", "").trim(),
                )
            }
            return out
        }
    }

    private lateinit var prefs: Prefs
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var searchBox: android.widget.EditText
    private var items: List<StoreItem> = emptyList()
    private val busy = HashSet<String>()
    /** Live download percent per item, and the progress bar currently showing it. */
    private val dlPercent = HashMap<String, Int>()
    private val dlBar = HashMap<String, android.widget.ProgressBar>()
    /** An APK waiting for the "allow installs" permission; installed when the user comes back. */
    private var pendingInstall: File? = null
    /** "Update all": APKs downloaded and waiting for Android's install screen, one after another. */
    private val installQueue = ArrayDeque<File>()
    private lateinit var updateAllBtn: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val c = this
        val box = Ui.vbox(c).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            val p = Ui.dp(c, 18)
            setPadding(p, p, p, p)
        }
        val head = Ui.hbox(c).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(Ui.label(c, "المتجر", 22f, bold = true), Ui.lp(0, Ui.WRAP, 1f))
        updateAllBtn = Ui.button(c, "حدّث الكل", primary = true) { updateAll() }.apply { visibility = View.GONE }
        head.addView(updateAllBtn, Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginEnd = Ui.dp(c, 8) })
        head.addView(Ui.button(c, "التطبيقات") { startActivity(Intent(c, AppsActivity::class.java)) },
            Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginEnd = Ui.dp(c, 8) })
        head.addView(Ui.button(c, "تحديث") { load() }, Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginEnd = Ui.dp(c, 8) })
        head.addView(Ui.button(c, "سكّر") { finish() })
        box.addView(head, Ui.lp(Ui.MATCH, Ui.WRAP))
        // Search: filters the list by app name or description as you type.
        searchBox = android.widget.EditText(c).apply {
            hint = "دوّر على برنامج..."
            setTextColor(Ui.WHITE)
            setHintTextColor(0x99FFFFFF.toInt())
            background = Ui.glass(c, 14f)
            setPadding(Ui.dp(c, 14), Ui.dp(c, 10), Ui.dp(c, 14), Ui.dp(c, 10))
            isSingleLine = true
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { render() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
            })
        }
        box.addView(searchBox, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(c, 8) })
        status = Ui.label(c, "", 14f, Ui.SUB)
        box.addView(status, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(c, 6); bottomMargin = Ui.dp(c, 8) })
        list = Ui.vbox(c)
        box.addView(ScrollView(c).apply {
            isVerticalScrollBarEnabled = false
            addView(list)
        }, Ui.lp(Ui.MATCH, 0, 1f))
        setContentView(box)
        Ui.dialogWindow(this, 720, 0.88f)
        load()
    }

    override fun onResume() {
        super.onResume()
        HubService.instance?.hideBarForSheet()
        val f = pendingInstall
        if (f != null && packageManager.canRequestPackageInstalls()) {
            pendingInstall = null
            install(f)
        } else if (installQueue.isNotEmpty() && packageManager.canRequestPackageInstalls()) {
            // Back from the previous install screen: show the next one.
            install(installQueue.removeFirst())
        } else if (items.isNotEmpty()) {
            render() // an install may have finished: refresh the "installed" labels
        }
    }

    private fun dir(): File = File(getExternalFilesDir(null) ?: filesDir, "store").apply { mkdirs() }

    // ---------------------------------------------------------------- list

    private fun load() {
        val url = prefs.storeUrl.trim()
        list.removeAllViews()
        if (url.isEmpty()) {
            status.text = "بعد ما حطيت رابط المتجر"
            list.addView(Ui.button(this, "روح للإعدادات", primary = true) {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }, Ui.lp(Ui.MATCH, Ui.WRAP))
            return
        }
        status.text = "دا يجيب القائمة..."
        Thread {
            val result = runCatching { parse(url, Net.getText(fresh(url))) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess {
                    items = it
                    render()
                }.onFailure {
                    status.text = "ما وصلت للمتجر: ${it.message ?: "خطأ بالشبكة"}"
                }
            }
        }.start()
    }

    private fun parse(base: String, text: String): List<StoreItem> = parseIndex(base, text)

    private fun render() {
        list.removeAllViews()
        val updates = items.count { state(it) == State.UPDATE }
        status.text = when {
            items.isEmpty() -> "القائمة فارغة"
            updates > 0 -> "${items.size} ملف · أكو $updates تحديث"
            else -> "${items.size} ملف"
        }
        updateAllBtn.visibility = if (updates > 1 && busy.isEmpty()) View.VISIBLE else View.GONE
        val q = searchBox.text.toString().trim()
        val shown = if (q.isEmpty()) items else items.filter {
            it.name.contains(q, true) || it.note.contains(q, true) || it.pkg.contains(q, true)
        }
        if (shown.isEmpty() && q.isNotEmpty()) status.text = "ماكو برنامج بهالاسم"
        var openCard: View? = null
        for (item in shown) {
            val v = itemRow(item)
            if (item.url == openNote) openCard = v
            list.addView(v, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = Ui.dp(this@StoreActivity, 10) })
        }
        // Keep the app whose description was just opened in view (the list may have changed order).
        openCard?.let { card ->
            card.post {
                val sv = list.parent as? ScrollView ?: return@post
                val r = android.graphics.Rect()
                card.getDrawingRect(r)
                sv.offsetDescendantRectToMyCoords(card, r)
                if (r.top < sv.scrollY || r.bottom > sv.scrollY + sv.height) sv.smoothScrollTo(0, (r.top - Ui.dp(this, 8)).coerceAtLeast(0))
            }
        }
    }

    /** The app whose description is open (only one at a time). */
    private var openNote: String? = null

    private fun itemRow(item: StoreItem): View {
        val c = this
        val open = openNote == item.url && item.note.isNotEmpty()
        val card = Ui.vbox(c).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = if (open) glowCard() else Ui.glass(c, 18f)
            setPadding(Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 14), Ui.dp(c, 12))
            if (open && android.os.Build.VERSION.SDK_INT >= 28) {
                elevation = Ui.dp(c, 14).toFloat()
                outlineAmbientShadowColor = Ui.ACCENT
                outlineSpotShadowColor = Ui.ACCENT
            }
        }
        val row = Ui.hbox(c).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
        }
        card.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP))
        // Tapping the app shows its description (and hides the one that was open).
        if (item.note.isNotEmpty()) card.setOnClickListener {
            openNote = if (open) null else item.url
            render()
        }
        val installedIcon = if (item.pkg.isNotEmpty()) Apps.icon(c, item.pkg) else null
        val iconView = ImageView(c).apply {
            if (installedIcon != null) {
                setImageDrawable(installedIcon)
            } else {
                setImageResource(R.drawable.ic_store)
                setColorFilter(Ui.WHITE)
            }
        }
        row.addView(iconView, LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)).apply { marginEnd = Ui.dp(c, 12) })
        // If not installed, show the app's own icon from the store (downloaded once and cached).
        if (installedIcon == null && item.icon.isNotEmpty()) loadIcon(item.icon, iconView)

        val texts = Ui.vbox(c)
        val title = if (item.version.isNotEmpty()) "${item.name}  ${item.version}" else item.name
        texts.addView(Ui.label(c, title, 17f, bold = true))
        val sub = Ui.label(c, subtitle(item), 14f, Ui.SUB)
        texts.addView(sub)
        // While downloading, a progress bar under the name fills up as the app downloads.
        if (busy.contains(item.url)) {
            val pct = dlPercent[item.url]
            val bar = android.widget.ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                isIndeterminate = pct == null
                if (pct != null) progress = pct
                progressTintList = android.content.res.ColorStateList.valueOf(Ui.WHITE)
                indeterminateTintList = android.content.res.ColorStateList.valueOf(Ui.WHITE)
            }
            texts.addView(bar, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(c, 8) })
            dlBar[item.url] = bar
        }
        row.addView(texts, Ui.lp(0, Ui.WRAP, 1f))

        val st = state(item)
        val file = localFile(item)
        val installed = item.pkg.isNotEmpty() && Apps.isInstalled(c, item.pkg)
        fun addBtn(text: String, primary: Boolean = false, onClick: () -> Unit) {
            row.addView(Ui.button(c, text, primary = primary, onClick = onClick),
                Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginStart = Ui.dp(c, 8) })
        }
        if (busy.contains(item.url)) {
            // downloading: the progress bar already shows; no buttons
        } else {
            // Download / update / install action.
            when {
                st == State.OTHER_SIGNER -> {} // can't install over the old one: the subtitle says why
                st == State.UPDATE -> addBtn("حدّث", primary = true) { if (file != null) useFile(item, file) else download(item, sub) }
                st == State.LATEST || st == State.INSTALLED -> {} // installed, nothing to download
                !item.isApk && file != null -> addBtn("افتح", primary = true) { useFile(item, file) }
                file != null -> addBtn("نصّب", primary = true) { useFile(item, file) }
                else -> addBtn("نزّل", primary = true) { download(item, sub) }
            }
            // For an installed app: open it, and uninstall it from the car.
            if (installed) {
                addBtn("افتح") { Apps.launch(c, item.pkg) }
                addBtn("احذف") { uninstall(item.pkg) }
            } else if (file != null) {
                // A leftover downloaded file (app not installed): let the user clear it.
                addBtn("امسح الملف") { file.delete(); shaFile(file).delete(); render() }
            }
        }
        // Description behind a button; opening one closes the one that was open.
        if (item.note.isNotEmpty()) {
            addBtn(if (open) "سدّ الوصف" else "الوصف") {
                openNote = if (open) null else item.url
                render()
            }
        }
        if (open) {
            val text = Ui.label(c, item.note, 16f, Ui.WHITE).apply {
                setLineSpacing(0f, 1.25f)
                setTextIsSelectable(true)
                alpha = 0f
                animate().alpha(1f).setDuration(220).start()
            }
            card.addView(View(c).apply { setBackgroundColor(0x33FFFFFF) }, Ui.lp(Ui.MATCH, Ui.dp(c, 1)).apply { topMargin = Ui.dp(c, 12); bottomMargin = Ui.dp(c, 10) })
            card.addView(text, Ui.lp(Ui.MATCH, Ui.WRAP))
        }
        return card
    }

    /** Card of the app whose description is open: blue edge and a soft blue glow. */
    private fun glowCard(): android.graphics.drawable.Drawable = android.graphics.drawable.GradientDrawable(
        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(0x402E8BFF, 0x1A2E8BFF)
    ).apply {
        cornerRadius = Ui.dp(this@StoreActivity, 18).toFloat()
        setStroke(Ui.dp(this@StoreActivity, 2), Ui.ACCENT)
    }

    /** Asks Android to uninstall [pkg]; the system shows its own confirm screen. */
    private fun uninstall(pkg: String) {
        val i = Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
        if (runCatching { startActivity(i) }.isFailure) status.text = "ما انفتحت شاشة الحذف"
    }

    private fun subtitle(item: StoreItem): String {
        val parts = ArrayList<String>()
        if (item.size > 0) parts += sizeText(item.size)
        if (state(item) == State.OTHER_SIGNER) {
            parts += "النسخة الجديدة موقّعة من مطوّر ثاني، ما تنصب فوق المنصّبة"
            return parts.joinToString(" · ")
        }
        if (item.pkg.isNotEmpty() && Apps.isInstalled(this, item.pkg)) {
            val info = runCatching { packageManager.getPackageInfo(item.pkg, 0) }.getOrNull()
            val have = info?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0
            parts += when {
                item.versionCode > 0 && have >= item.versionCode -> "منصّب، آخر نسخة"
                item.versionCode > 0 -> "منصّب (${info?.versionName ?: have}). أكو تحديث"
                else -> "منصّب (${info?.versionName ?: ""})"
            }
        }
        return parts.joinToString(" · ")
    }

    private fun state(item: StoreItem): State = stateOf(this, item)

    /**
     * The downloaded copy of [item], or null. A copy from an older version of the same file name
     * (e.g. zmd-car.apk before an update) does not match the new fingerprint and is thrown away,
     * so the old version is never installed by mistake. Copies of apps already up to date are
     * removed to free space.
     */
    private fun localFile(item: StoreItem): File? {
        val f = File(dir(), item.fileName)
        if (!f.isFile) return null
        val stale = item.sha256.isNotEmpty() &&
            runCatching { shaFile(f).readText().trim() }.getOrDefault("") != item.sha256
        if (stale || state(item) == State.LATEST) {
            f.delete()
            shaFile(f).delete()
            return null
        }
        return f
    }

    private val iconMemory = HashMap<String, android.graphics.Bitmap>()

    /** Loads an app icon from [url], caching it in memory and on disk, then shows it in [view]. */
    private fun loadIcon(url: String, view: ImageView) {
        iconMemory[url]?.let { view.setImageBitmap(it); view.clearColorFilter(); return }
        Thread {
            val cacheDir = File(dir(), "icons").apply { mkdirs() }
            val f = File(cacheDir, url.hashCode().toUInt().toString())
            val bmp = runCatching {
                if (!f.isFile || f.length() == 0L) Net.download(url, f) { _, _ -> }
                android.graphics.BitmapFactory.decodeFile(f.path)
            }.getOrNull() ?: return@Thread
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                iconMemory[url] = bmp
                view.setImageBitmap(bmp)
                view.clearColorFilter()
            }
        }.start()
    }

    private fun shaFile(f: File) = File(f.parentFile, f.name + ".sha256")

    private fun sizeText(b: Long): String =
        if (b >= 1 shl 20) String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0) else "${(b + 1023) / 1024} KB"

    /** Downloads every app that has a newer version, then shows Android's install screen for each in turn. */
    private fun updateAll() {
        val todo = items.filter { state(it) == State.UPDATE && it.isApk }
        if (todo.isEmpty()) return
        todo.forEach { busy += it.url }
        render()
        Thread {
            val ready = ArrayList<File>()
            for (item in todo) {
                val f = localFile(item) ?: fetch(item)
                if (f != null) ready += f
                runOnUiThread { busy -= item.url }
            }
            runOnUiThread {
                busy.clear()
                if (isFinishing || isDestroyed) return@runOnUiThread
                render()
                status.text = if (ready.size == todo.size) "نزلت كلها. وافق على كل تنصيب" else "نزل ${ready.size} من ${todo.size}"
                installQueue.clear()
                installQueue.addAll(ready)
                if (installQueue.isNotEmpty()) install(installQueue.removeFirst())
            }
        }.start()
    }

    /** Background download for "update all": the saved file, or null if it failed or did not match. */
    private fun fetch(item: StoreItem): File? {
        val dest = File(dir(), item.fileName)
        val hash = runCatching {
            Net.download(item.url, dest) { done, total ->
                val pct = if (total > 0) (done * 100 / total).toInt() else -1
                val txt = if (pct >= 0) "$pct٪" else "${done / 1024} KB"
                runOnUiThread {
                    if (pct >= 0) {
                        dlPercent[item.url] = pct
                        dlBar[item.url]?.apply { isIndeterminate = false; progress = pct }
                    }
                    status.text = "${item.name}: دا ينزل $txt"
                }
            }
        }.getOrNull()
        runOnUiThread { dlPercent.remove(item.url); dlBar.remove(item.url) }
        if (hash == null || (item.sha256.isNotEmpty() && hash != item.sha256)) {
            dest.delete()
            return null
        }
        shaFile(dest).writeText(hash)
        return dest
    }

    // ---------------------------------------------------------------- download + install

    private fun download(item: StoreItem, sub: TextView) {
        busy += item.url
        render()
        val dest = File(dir(), item.fileName)
        Thread {
            val result = runCatching {
                Net.download(item.url, dest) { done, total ->
                    val pct = if (total > 0) (done * 100 / total).toInt() else -1
                    val txt = if (pct >= 0) "دا ينزل $pct٪" else "دا ينزل ${done / 1024} KB"
                    runOnUiThread {
                        if (pct >= 0) {
                            dlPercent[item.url] = pct
                            dlBar[item.url]?.apply { isIndeterminate = false; progress = pct }
                        }
                        status.text = "${item.name}: $txt"
                    }
                }
            }
            runOnUiThread {
                busy -= item.url
                dlPercent.remove(item.url)
                dlBar.remove(item.url)
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { hash ->
                    if (item.sha256.isNotEmpty() && hash != item.sha256) {
                        dest.delete()
                        status.text = "${item.name}: الملف ما يطابق البصمة، انمسح. نزّله مرة ثانية"
                        render()
                        return@onSuccess
                    }
                    shaFile(dest).writeText(hash)
                    status.text = "${item.name}: نزل"
                    render()
                    useFile(item, dest)
                }.onFailure {
                    status.text = "${item.name}: ما نزل (${it.message ?: "خطأ"})"
                    render()
                }
            }
        }.start()
        sub.text = "دا ينزل..."
    }

    private fun useFile(item: StoreItem, file: File) {
        if (item.isApk) install(file) else openFile(file)
    }

    private fun uri(file: File): Uri = FileProvider.getUriForFile(this, "$packageName.files", file)

    /** Hands the APK to Android's installer, which shows its own confirm screen. */
    private fun install(file: File) {
        // A visible copy in Download/ZMD Car too, for installing by hand from a file manager
        // (skipped when the same file is already there).
        PublicCopy.saveApkLater(this, file)
        if (!packageManager.canRequestPackageInstalls()) {
            pendingInstall = file
            status.text = "اسمح للبرنامج يحط برامج، وارجع. التنصيب يكمل وحده"
            val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            if (runCatching { startActivity(i) }.isFailure) status.text = "الشاشة ما بيها إعداد السماح بالتنصيب"
            return
        }
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri(file), "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { startActivity(i) }.isFailure) status.text = "ما انفتح المنصّب"
    }

    private fun openFile(file: File) {
        val ext = file.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri(file), mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { startActivity(Intent.createChooser(i, file.name)) }.isFailure) {
            status.text = "ماكو برنامج يفتح هالنوع"
        }
    }
}
