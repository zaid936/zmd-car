package com.zmd.carhub

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView

/** A glass sheet listing the apps installed on the screen; the pick is saved straight to settings. */
class AppPickerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = intent.getStringExtra(TARGET) ?: MAP
        val prefs = Prefs(this)
        // For the map, the hub's own built-in map comes first, then ONLY map/navigation apps.
        val apps = if (target == MAP) {
            val maps = mapPackages()
            listOf(AppEntry(packageName, "خريطة ${getString(R.string.app_name)} (المدمجة)", Apps.icon(this, packageName))) +
                Apps.launchable(this).filter { it.pkg in maps }
        } else {
            Apps.launchable(this)
        }

        val box = Ui.vbox(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            val p = Ui.dp(context, 16)
            setPadding(p, p, p, p)
        }
        val title = when (target) {
            CARPLAY -> "اختار برنامج CarPlay"
            AUTO -> "اختار برنامج Android Auto"
            SPLIT -> if (intent.getBooleanExtra(WITH_CURRENT, false)) "اختار البرنامج اللي يطلع جنب البرنامج المفتوح" else "اختار البرنامج اللي يطلع يم الخريطة"
            else -> "اختار برنامج الخرائط"
        }
        box.addView(Ui.label(this, title, 20f, bold = true), Ui.lp(Ui.MATCH, Ui.WRAP).apply {
            bottomMargin = Ui.dp(this@AppPickerActivity, 10)
        })

        val list = ListView(this).apply {
            divider = null
            isVerticalScrollBarEnabled = false
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        list.adapter = object : BaseAdapter() {
            override fun getCount(): Int = apps.size
            override fun getItem(position: Int): Any = apps[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val c = this@AppPickerActivity
                val app = apps[position]
                val row = Ui.hbox(c).apply {
                    layoutDirection = View.LAYOUT_DIRECTION_RTL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(Ui.dp(c, 10), Ui.dp(c, 10), Ui.dp(c, 10), Ui.dp(c, 10))
                    minimumHeight = Ui.dp(c, 64)
                }
                row.addView(ImageView(c).apply { setImageDrawable(app.icon) },
                    LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)).apply { marginEnd = Ui.dp(c, 14) })
                row.addView(Ui.label(c, app.label, 18f), Ui.lp(0, Ui.WRAP, 1f))
                return row
            }
        }
        list.setOnItemClickListener { _, _, position, _ ->
            val pkg = apps[position].pkg
            when (target) {
                CARPLAY -> prefs.carplayPackage = pkg
                AUTO -> prefs.autoPackage = pkg
                SPLIT -> {
                    prefs.splitPackage = pkg
                    // When opened from the split button, start the split right after choosing.
                    if (intent.getBooleanExtra(START_AFTER, false)) {
                        HubService.instance?.startChosenSplit(intent.getBooleanExtra(WITH_CURRENT, false))
                    }
                }
                else -> {
                    prefs.mapPackage = if (pkg == packageName) null else pkg
                    // Open the chosen map right away (Waze, Darbouna... or our own map).
                    HubService.instance?.openMap()
                }
            }
            HubService.instance?.refreshOverlay()
            finish()
        }
        box.addView(list, Ui.lp(Ui.MATCH, 0, 1f))
        setContentView(box)
        Ui.dialogWindow(this, 560, 0.84f)
    }

    override fun onResume() {
        super.onResume()
        HubService.instance?.hideBarForSheet()
    }

    /** Packages that can act as a map / navigation app (handle geo: or turn-by-turn, or are map launchers). */
    private fun mapPackages(): Set<String> {
        val pm = packageManager
        val set = HashSet<String>()
        val probes = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")),
            Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=0,0")),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MAPS)
        )
        for (i in probes) runCatching {
            pm.queryIntentActivities(i, 0).forEach { set.add(it.activityInfo.packageName) }
        }
        set.remove(packageName)
        return set
    }

    companion object {
        const val TARGET = "target"
        const val MAP = "map"
        const val CARPLAY = "carplay"
        const val AUTO = "auto"
        const val SPLIT = "split"
        // For the SPLIT target opened from the split button: start the split once an app is chosen.
        const val START_AFTER = "start_after"
        const val WITH_CURRENT = "with_current"
    }
}
