package com.zmd.carhub

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/** Marks the map being opened by the home button (so it does not jump to another maps app). */
const val EXTRA_FROM_HOME = "com.zmd.carhub.FROM_HOME"

/**
 * The screen's home (launcher) when the user picks ZMD Car: the home button brings up our map.
 * The map runs in its own normal task so it can still share the screen in split.
 *
 * Only a real home press (or the home showing up and staying, e.g. the last app closed) opens
 * the map. Home also flashes by for a moment while apps switch or the screen splits; jumping to
 * the map then would cover the app the user just opened (seen on the car, 2026-11-17).
 */
class HomeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val showMap = Runnable { openMap() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openMap()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The home button.
        handler.removeCallbacks(showMap)
        openMap()
    }

    override fun onResume() {
        super.onResume()
        // Uncovered without a home press: open the map only if home stays on screen.
        handler.removeCallbacks(showMap)
        handler.postDelayed(showMap, 900)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(showMap)
    }

    private fun openMap() {
        if (HubService.instance?.splitBusy() == true) return
        // The user chose another maps app (Waze...): home opens that, with our side bar over it.
        val chosen = Prefs(this).mapPackage
        if (chosen != null && chosen != packageName) {
            // Straight into the space beside the side bar when windows can be resized.
            if (Perms.freeformOn(this)) {
                // Our map behind (so the strip around the side bar is map), the chosen one beside the bar.
                startActivity(Intent(this, MapActivity::class.java).setAction(Intent.ACTION_MAIN)
                    .putExtra(EXTRA_FROM_HOME, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                handler.postDelayed({ launchBeside(this, chosen, barWidth(this)) }, 250)
                return
            }
            if (Apps.launch(this, chosen)) return
        }
        startActivity(Intent(this, MapActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .putExtra(EXTRA_FROM_HOME, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
