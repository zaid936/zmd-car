package com.zmd.carhub

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * A simple launcher: every app installed on the car screen, shown as an icon grid. Tapping one
 * opens it. Reached from the store ("التطبيقات"). Useful on car systems with no normal home screen.
 */
class AppsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        window.setBackgroundDrawable(Ui.appBackground(this))
        render()
    }

    override fun onResume() {
        super.onResume()
        HubService.instance?.hideBarForSheet()
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun render() {
        val c = this
        val root = Ui.vbox(c).apply { setPadding(dp(20), dp(18), dp(20), dp(24)) }

        val head = Ui.hbox(c).apply { gravity = Gravity.CENTER_VERTICAL }
        val titles = Ui.vbox(c)
        titles.addView(Ui.label(c, "التطبيقات", 24f, bold = true))
        titles.addView(Ui.label(c, "كل البرامج المنصّبة على الشاشة — دوس حتى تفتح", 14f, Ui.SUB))
        head.addView(titles, Ui.lp(0, Ui.WRAP, 1f))
        head.addView(Ui.button(c, "سكّر") { finish() })
        root.addView(head, Ui.lp(Ui.MATCH, Ui.WRAP))

        val apps = Apps.launchable(c)
        val cols = (resources.configuration.screenWidthDp / 150).coerceIn(3, 8)
        val grid = GridLayout(c).apply {
            columnCount = cols
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val cell = (resources.displayMetrics.widthPixels - dp(40)) / cols
        for (app in apps) {
            val item = Ui.vbox(c).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                background = Ui.ripple(c, null, 18f)
                setPadding(dp(8), dp(14), dp(8), dp(14))
                isClickable = true
                setOnClickListener { Apps.launch(c, app.pkg) }
            }
            item.addView(ImageView(c).apply {
                if (app.icon != null) setImageDrawable(app.icon) else {
                    setImageResource(R.drawable.ic_store); setColorFilter(Ui.WHITE)
                }
            }, LinearLayout.LayoutParams(dp(56), dp(56)))
            item.addView(Ui.label(c, app.label, 13f, Ui.WHITE).apply {
                gravity = Gravity.CENTER
                textAlignment = TextView.TEXT_ALIGNMENT_CENTER
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(6) })
            grid.addView(item, GridLayout.LayoutParams().apply {
                width = cell
                height = GridLayout.LayoutParams.WRAP_CONTENT
                setMargins(dp(2), dp(2), dp(2), dp(2))
            })
        }
        root.addView(grid, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(12) })

        setContentView(ScrollView(c).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(root)
        })
    }
}
