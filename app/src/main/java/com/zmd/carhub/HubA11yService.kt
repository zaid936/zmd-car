package com.zmd.carhub

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * The helper the user switches on in the screen's accessibility settings. It does four things:
 * remembers which app is on screen (package name only), tells whether the screen is split (by
 * looking at the window LIST), drives the split-screen sequence, and (only if the user turns that option on) taps
 * «Allow» on the system's USB prompt when CarPlay (DiPlay) asks for the plugged-in iPhone. That
 * prompt is the only window whose contents it ever looks at.
 *
 * The split sequence copies what working split-screen launchers do (e.g. split-screen-launcher on
 * GitHub): it is EVENT-DRIVEN, not fixed delays — wait for the first app to be on screen, fire the
 * system split action, wait for the split UI to appear, then open the second app into the free half.
 * Small fallback timers cover screens that don't emit the expected events.
 */
class HubA11yService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
        // See the window list (to detect split and the divider). Still no content reading.
        runCatching {
            serviceInfo = serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString()
        if (pkg == SYSTEM_UI && event.className?.toString()?.endsWith("UsbPermissionActivity") == true) {
            usbPromptSeen = SystemClock.elapsedRealtime()
            allowCarPlayUsb(0)
        }
        // Android Auto is built in: its screen is ours too (same package), so tell it apart by class.
        val androidAuto = pkg == packageName && event.className?.toString() == AA_SCREEN
        if (androidAuto || (pkg != null && pkg != packageName && pkg != SYSTEM_UI)) {
            foreground = pkg
            mapOnTop = false
        } else if (pkg == packageName && event.className?.toString()?.endsWith(".MapActivity") == true) {
            // Our own map is the app on screen (our sheets and pickers don't change this).
            mapOnTop = true
        }
        driveSplit(pkg)
        if (pkg != null && pkg != SYSTEM_UI) {
            HubService.instance?.onAppOnScreen(if (pkg == packageName && !androidAuto) null else pkg, mapOnTop)
        }
    }

    // ---------------------------------------------------------------- CarPlay's USB prompt

    private var usbPromptSeen = 0L

    /**
     * DiPlay asks Android for USB access to the iPhone every time the phone connects, and the
     * iPhone drops off the cable and comes back about a second later, which closes the prompt
     * before anyone can tap it (seen in the car's log, 2026-11-17). Only if the user turned the
     * option on (off by default), the hub taps «Allow» on that one prompt, and only when: the prompt is on screen
     * now, it names the CarPlay app (DiPlay), and an iPhone is plugged in. Nothing else is read.
     */
    private fun allowCarPlayUsb(attempt: Int) {
        if (!autoAllowCarPlayUsb(this)) return
        if (HubService.instance?.iphoneOnCable() != true) return
        if (SystemClock.elapsedRealtime() - usbPromptSeen > 3000) return
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(Prefs.DEFAULT_CARPLAY, 0)).toString()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
        val prompt = runCatching {
            (listOfNotNull(rootInActiveWindow) + windows.mapNotNull { it.root })
                .firstOrNull { it.packageName == SYSTEM_UI && it.findAccessibilityNodeInfosByText(label).isNotEmpty() }
        }.getOrNull()
        val allow = prompt?.findAccessibilityNodeInfosByViewId("android:id/button1")?.firstOrNull { it.isEnabled && it.isVisibleToUser }
        if (allow == null) {
            // The dialog may still be filling in, or its button not enabled yet.
            if (attempt < 10) handler.postDelayed({ allowCarPlayUsb(attempt + 1) }, 100)
            return
        }
        allow.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    // ---------------------------------------------------------------- split state machine

    private var base: String? = null
    private var second: String? = null
    // 0 = idle, 1 = waiting for the base app on screen (then toggle split), 2 = waiting for the
    // split UI (then launch the second app).
    private var stage = 0

    /** Begin a split. The caller launches the [basePkg] first; this watches for it, then does the rest. */
    fun beginSplit(basePkg: String, secondPkg: String) {
        base = basePkg
        second = secondPkg
        stage = 1
        // Fallback: if the "base is on screen" event never comes, go on anyway shortly.
        handler.postDelayed({ if (stage == 1) baseOnScreen() }, 1200)
    }

    fun cancelSplit() {
        stage = 0; base = null; second = null
    }

    private fun driveSplit(pkg: String?) {
        when (stage) {
            1 -> if (pkg != null && pkg == base) baseOnScreen()
            2 -> if (hasDivider() || appWindows() >= 2) launchSecondPane()
        }
    }

    /**
     * The base app is on screen. Android 12L+ (the car is 13): open the second app straight next
     * to it (FLAG_ACTIVITY_LAUNCH_ADJACENT), which splits the screen with no detour. The old
     * system split action is only a fallback: on these versions it first throws the home screen
     * up (seen on the car, 2026-11-17), which the user called «السستم القديم».
     */
    private fun baseOnScreen() {
        if (stage != 1) return
        if (android.os.Build.VERSION.SDK_INT >= 32) {
            stage = 3
            launchAdjacent(second)
            handler.postDelayed({
                if (stage != 3) return@postDelayed
                if (hasDivider() || appWindows() >= 2) {
                    finishSplit()
                } else {
                    // Not split: try the system action once, then drop the second app in again.
                    stage = 2
                    performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
                    handler.postDelayed({ if (stage == 2) launchSecondPane() }, 1500)
                }
            }, 1600)
        } else {
            stage = 2
            performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
            // Fallback: if we never see the split UI, drop the second app in anyway.
            handler.postDelayed({ if (stage == 2) launchSecondPane() }, 1500)
        }
    }

    private fun launchAdjacent(pkg: String?) {
        if (pkg == null) return
        val i = if (pkg == packageName) Intent().setClassName(packageName, "$packageName.MapActivity")
        else packageManager.getLaunchIntentForPackage(pkg)
        if (i != null) {
            i.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            )
            runCatching { startActivity(i) }
        }
    }

    private fun launchSecondPane() {
        if (stage != 2) return
        launchAdjacent(second)
        finishSplit()
    }

    private fun finishSplit() {
        stage = 0; base = null; second = null
        handler.postDelayed({ HubService.instance?.onSplitSettled() }, 800)
    }

    private fun hasDivider(): Boolean = try {
        windows.any { it.type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER }
    } catch (e: Exception) {
        false
    }

    /** How many real app windows share the screen (ignores our overlays and the system bars). */
    private fun appWindows(): Int = try {
        windows.count { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
    } catch (e: Exception) {
        -1
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        handler.removeCallbacksAndMessages(null)
        cancelSplit()
        instance = null
        return super.onUnbind(intent)
    }

    companion object {
        private const val SYSTEM_UI = "com.android.systemui"
        /** The built-in Android Auto screen (ZMD Auto engine, runs in our ":auto" process). */
        private const val AA_SCREEN = "com.andrerinas.openheadunit.aap.AapProjectionActivity"
        /**
         * SharedPreferences "hub" key: tap «Allow» on CarPlay's USB prompt by itself. Default OFF:
         * the user said they want to tap that prompt themselves; it is their switch to turn on.
         */
        const val KEY_AUTO_ALLOW_USB = "auto_allow_carplay_usb"

        fun autoAllowCarPlayUsb(ctx: android.content.Context): Boolean =
            ctx.getSharedPreferences("hub", android.content.Context.MODE_PRIVATE).getBoolean(KEY_AUTO_ALLOW_USB, false)

        fun setAutoAllowCarPlayUsb(ctx: android.content.Context, on: Boolean) {
            ctx.getSharedPreferences("hub", android.content.Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO_ALLOW_USB, on).apply()
        }

        @Volatile
        var instance: HubA11yService? = null
            private set

        /** Package of the app currently on screen, as far as this service has seen. */
        @Volatile
        var foreground: String? = null
            private set

        /** The hub's own map is the app on screen (rather than [foreground]). */
        @Volatile
        var mapOnTop: Boolean = false
            private set

        /**
         * The package of the app window on top right now, read from the live window list (works
         * even right after the helper restarted, when [foreground] is still unknown). Our own
         * package means our map (or another hub screen) is on top. null = can't tell.
         */
        fun topAppPackage(): String? {
            val svc = instance ?: return null
            return runCatching {
                svc.windows
                    .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                    .sortedByDescending { it.layer }
                    .firstNotNullOfOrNull { it.root?.packageName?.toString() }
            }.getOrNull()
        }

        /**
         * A system dialog (such as Android's USB permission prompt) is on screen now: an app window
         * that belongs to SystemUI. null = can't tell (helper off).
         */
        fun systemDialogOpen(): Boolean? {
            val svc = instance ?: return null
            return runCatching {
                svc.windows.any { w -> w.type == AccessibilityWindowInfo.TYPE_APPLICATION && w.root?.packageName == SYSTEM_UI }
            }.getOrNull()
        }

        /** Left edge on screen of [pkg]'s app window, or null if it isn't showing / can't tell. */
        fun appWindowLeft(pkg: String): Int? {
            val svc = instance ?: return null
            return runCatching {
                svc.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root?.packageName == pkg }
                    ?.let { w -> android.graphics.Rect().also { w.getBoundsInScreen(it) }.left }
            }.getOrNull()
        }

        /** true = the screen is split now; false = it is not; null = can't tell / service off. */
        fun splitActive(): Boolean? {
            val svc = instance ?: return null
            if (svc.hasDivider()) return true
            val n = svc.appWindows()
            return if (n < 0) null else n >= 2
        }

        /** Begin the event-driven split; the caller must launch [basePkg] right after. false = service off. */
        fun startSplit(basePkg: String, secondPkg: String): Boolean {
            val svc = instance ?: return false
            svc.beginSplit(basePkg, secondPkg)
            return true
        }

        fun cancel() {
            instance?.cancelSplit()
        }

        /** The plain system split toggle, used to LEAVE split. false = the service isn't running. */
        fun toggleSplit(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN) ?: false
    }
}
