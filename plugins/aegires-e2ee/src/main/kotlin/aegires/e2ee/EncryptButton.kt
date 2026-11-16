package aegires.e2ee

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger

/**
 * The small "E2EE" on/off button that sits in the channel header, just left of Discord's search icon.
 *
 * Discord draws its screens with React Native, so a view cannot be added to its header safely (the
 * layout engine owns those views). The button is therefore a separate native overlay on the activity's
 * window. Each time the window's layout changes it looks for the header's search icon (a view whose
 * accessibility label contains "search", near the top of the screen) and puts itself to the left of it.
 * No search icon on screen (a different screen, a different language) means no button.
 *
 * The JS side decides what the button shows ([set]) and polls [takeTaps] to learn about presses.
 * States: "on", "off", "locked" (built-in key: always on, a press only reports), "hide".
 */
internal object EncryptButton {
    private const val HEADER_DP = 200 // the search icon lives in the top 200 dp
    private const val MAX_ICON_DP = 96 // and is a small view

    private var view: TextView? = null
    private var host: WeakReference<Activity>? = null
    private var listener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var searchIcon: WeakReference<View>? = null
    private var state = "hide"
    private var placing = false
    private val taps = AtomicInteger(0)

    /** Presses since the last call. */
    fun takeTaps(): Int = taps.getAndSet(0)

    fun set(activity: Activity, newState: String) {
        activity.runOnUiThread {
            try {
                state = if (newState == "on" || newState == "off" || newState == "locked") newState else "hide"
                if (state == "hide") {
                    detach()
                    return@runOnUiThread
                }
                attach(activity)
                style()
                place()
            } catch (_: Throwable) {
                // A cosmetic overlay must never crash the app.
                detach()
            }
        }
    }

    fun remove(activity: Activity?) {
        when {
            activity != null -> activity.runOnUiThread { detach() }
            view != null -> view?.post { detach() } // stop(): no activity at hand, go through the view's own handler
            else -> detach()
        }
    }

    // ------------------------------------------------------------------ the overlay view

    private fun dp(a: Activity, v: Float): Int = (v * a.resources.displayMetrics.density + 0.5f).toInt()

    private fun attach(activity: Activity) {
        val same = host?.get() === activity && view?.parent != null
        if (same) return
        detach()
        val decor = activity.window.decorView as? ViewGroup ?: return
        val tv = TextView(activity)
        tv.text = "E2EE"
        tv.textSize = 11f
        tv.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        tv.gravity = Gravity.CENTER
        tv.setPadding(dp(activity, 9f), dp(activity, 4f), dp(activity, 9f), dp(activity, 4f))
        tv.isClickable = true
        tv.contentDescription = "Encryption switch"
        tv.setOnClickListener {
            taps.incrementAndGet()
            // Show the change at once; the JS side confirms (or corrects) it a moment later.
            if (state == "on") state = "off" else if (state == "off") state = "on"
            style()
        }
        tv.visibility = View.INVISIBLE
        decor.addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        view = tv
        host = WeakReference(activity)
        val l = ViewTreeObserver.OnGlobalLayoutListener { place() }
        decor.viewTreeObserver.addOnGlobalLayoutListener(l)
        listener = l
    }

    private fun detach() {
        val tv = view
        val a = host?.get()
        val l = listener
        try {
            if (a != null && l != null) {
                val vto = (a.window.decorView as? ViewGroup)?.viewTreeObserver
                if (vto != null && vto.isAlive) vto.removeOnGlobalLayoutListener(l)
            }
            (tv?.parent as? ViewGroup)?.removeView(tv)
        } catch (_: Throwable) {
        }
        view = null
        host = null
        listener = null
        searchIcon = null
    }

    private fun style() {
        val tv = view ?: return
        val a = host?.get() ?: return
        val bg = GradientDrawable()
        bg.cornerRadius = dp(a, 12f).toFloat()
        when (state) {
            "on", "locked" -> {
                bg.setColor(0xFF23A55A.toInt())
                tv.setTextColor(Color.WHITE)
                tv.text = "E2EE"
            }
            else -> {
                bg.setColor(0x33808080)
                bg.setStroke(dp(a, 1f), 0xFF80848E.toInt())
                tv.setTextColor(0xFFB5BAC1.toInt())
                tv.text = "E2EE"
            }
        }
        tv.background = bg
        tv.alpha = if (state == "locked") 0.85f else 1f
    }

    // ------------------------------------------------------------------ placement

    private fun place() {
        if (placing) return
        placing = true
        try {
            val tv = view ?: return
            val a = host?.get() ?: return
            val decor = a.window.decorView as? ViewGroup ?: return
            val icon = findSearchIcon(a, decor)
            if (icon == null) {
                tv.visibility = View.INVISIBLE
                return
            }
            tv.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val iconLoc = IntArray(2)
            val decorLoc = IntArray(2)
            icon.getLocationOnScreen(iconLoc)
            decor.getLocationOnScreen(decorLoc)
            val gap = dp(a, 4f)
            val x = iconLoc[0] - decorLoc[0] - gap - tv.measuredWidth
            val y = iconLoc[1] - decorLoc[1] + (icon.height - tv.measuredHeight) / 2
            tv.translationX = x.toFloat()
            tv.translationY = y.toFloat()
            tv.visibility = View.VISIBLE
            // Only when something was added after us: bringToFront() requests a layout, which would call place() again, forever.
            if (decor.getChildAt(decor.childCount - 1) !== tv) tv.bringToFront()
        } finally {
            placing = false
        }
    }

    private fun findSearchIcon(a: Activity, decor: ViewGroup): View? {
        val cached = searchIcon?.get()
        if (cached != null && cached.isAttachedToWindow && cached.isShown) return cached
        val best = arrayOfNulls<View>(1)
        val bestX = IntArray(1) { Int.MIN_VALUE }
        walk(decor, 0, dp(a, HEADER_DP.toFloat()), dp(a, MAX_ICON_DP.toFloat()), best, bestX)
        searchIcon = best[0]?.let { WeakReference(it) }
        return best[0]
    }

    /** Depth-first over the top of the screen only; [offY] is the window-relative top of [v]'s children's parent. */
    private fun walk(v: View, offY: Int, limit: Int, maxIcon: Int, best: Array<View?>, bestX: IntArray) {
        if (v === view || v.visibility != View.VISIBLE) return
        val d = v.contentDescription
        if (d != null && d.contains("search", ignoreCase = true) && v.width in 1..maxIcon && v.height in 1..maxIcon) {
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            if (loc[0] > bestX[0] && v.isShown) {
                bestX[0] = loc[0]
                best[0] = v
            }
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val c = v.getChildAt(i)
                val top = offY + c.top - v.scrollY
                if (top > limit) continue
                walk(c, top, limit, maxIcon, best, bestX)
            }
        }
    }
}
