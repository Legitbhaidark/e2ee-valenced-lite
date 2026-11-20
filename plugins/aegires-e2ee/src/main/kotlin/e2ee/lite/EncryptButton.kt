package e2ee.lite

import android.app.Activity
import android.app.Application
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger

/**
 * The small "🔒 E2EE" / "🔒 OFF" button that sits in the channel header, just left of the header icons:
 * left of the search icon in a server channel, left of the call and video icons in a DM.
 *
 * Discord draws its screens with React Native, so a view cannot be added to its header safely (the
 * layout engine owns those views). The button is therefore a separate native overlay on the activity's
 * window. Each time the window's layout changes it looks for the header's search icon (a view whose
 * accessibility label contains "search", near the top of the screen) and puts itself to the left of the
 * leftmost icon of that row that is a search, call or video icon. No search icon on screen (a different
 * screen, a different language) means no button, and so does a screen without a message box (the DM list).
 *
 * The JS side decides what the button shows ([set]) and polls [takeTaps] to learn about presses.
 * States: "on", "off", "locked" (built-in key: always on, a press only reports), "hide".
 */
internal object EncryptButton {
    private const val HEADER_DP = 200 // the search icon lives in the top 200 dp
    private const val MAX_ICON_DP = 96 // and is a small view
    private val ICON_WORDS = listOf("search", "call", "video", "phone") // header icons the button must stay left of
    private const val LABEL_ON = "\uD83D\uDD12 E2EE" // lock + E2EE
    private const val LABEL_OFF = "\uD83D\uDD12 OFF" // lock + OFF

    private var view: TextView? = null
    private var host: WeakReference<Activity>? = null
    private var listener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var observer: ViewTreeObserver? = null
    private var lifecycle: Application.ActivityLifecycleCallbacks? = null
    private var app: Application? = null
    private var searchIcon: WeakReference<View>? = null // the leftmost header icon the button sits next to
    private var composer: WeakReference<View>? = null // the message box of the open chat
    private var composerScanAt = 0L
    private var preDraw: ViewTreeObserver.OnPreDrawListener? = null
    private var lastFull = 0L // last time place() ran the full checks
    private val screenRect = Rect() // reused every frame: follow() must not allocate
    private val tmpLoc = IntArray(2)
    private val tmpDecorLoc = IntArray(2)
    private var state = "hide"
    private var placing = false
    private var why = "not started" // why the button is shown or hidden right now (Diagnostics)
    private val taps = AtomicInteger(0)

    /** One line for Diagnostics: shown, or why it is hidden. */
    fun status(): String = why

    /** Presses since the last call. */
    fun takeTaps(): Int = taps.getAndSet(0)

    fun set(activity: Activity, newState: String) {
        activity.runOnUiThread {
            try {
                state = if (newState == "on" || newState == "off" || newState == "locked") newState else "hide"
                if (state == "hide") {
                    why = "hidden: switched off or no chat open"
                    detach()
                    return@runOnUiThread
                }
                watchLifecycle(activity)
                attach(activity)
                style()
                searchIcon = null // look again: the screen may have changed since the last call
                place()
            } catch (_: Throwable) {
                // A cosmetic overlay must never crash the app.
                detach()
            }
        }
    }

    /**
     * Coming back from the background, or from another screen (settings), Android may have rebuilt the
     * window: the overlay is put back at once instead of waiting for the next call from JS.
     */
    private fun watchLifecycle(activity: Activity) {
        if (lifecycle != null) return
        val application = activity.application ?: return
        val cb = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) {
                if (state == "hide") return
                a.runOnUiThread {
                    try {
                        attach(a)
                        style()
                        searchIcon = null
                        place()
                    } catch (_: Throwable) {
                    }
                }
            }

            override fun onActivityDestroyed(a: Activity) {
                if (host?.get() === a) detach()
            }

            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        }
        application.registerActivityLifecycleCallbacks(cb)
        lifecycle = cb
        app = application
    }

    /** Drops the lifecycle callbacks (plugin stop). */
    fun shutdown() {
        try {
            lifecycle?.let { app?.unregisterActivityLifecycleCallbacks(it) }
        } catch (_: Throwable) {
        }
        lifecycle = null
        app = null
        remove(null)
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
        if (same) {
            // The window's observer is replaced when the window is rebuilt, and our listener goes with it.
            val decorNow = activity.window.decorView as? ViewGroup
            val vto = decorNow?.viewTreeObserver
            if (vto != null && (vto !== observer || !vto.isAlive)) {
                unobserve()
                observe(vto)
            }
            return
        }
        detach()
        val decor = activity.window.decorView as? ViewGroup ?: return
        val tv = TextView(activity)
        tv.text = LABEL_ON
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
        observe(decor.viewTreeObserver)
        // Safety net: even if no layout callback arrives, the position is checked once a second.
        tv.postDelayed(object : Runnable {
            override fun run() {
                if (view !== tv || tv.parent == null) return
                place()
                tv.postDelayed(this, 1000)
            }
        }, 1000)
    }

    /** Layout passes run the full checks; every drawn frame only moves or hides the button (see [follow]). */
    private fun observe(vto: ViewTreeObserver) {
        val l = ViewTreeObserver.OnGlobalLayoutListener { place() }
        val p = ViewTreeObserver.OnPreDrawListener {
            follow()
            true
        }
        vto.addOnGlobalLayoutListener(l)
        vto.addOnPreDrawListener(p)
        listener = l
        preDraw = p
        observer = vto
    }

    private fun unobserve() {
        try {
            val vto = observer
            if (vto != null && vto.isAlive) {
                listener?.let { vto.removeOnGlobalLayoutListener(it) }
                preDraw?.let { vto.removeOnPreDrawListener(it) }
            }
        } catch (_: Throwable) {
        }
        listener = null
        preDraw = null
        observer = null
    }

    private fun detach() {
        val tv = view
        val a = host?.get()
        try {
            if (a != null && observer == null) {
                val vto = (a.window.decorView as? ViewGroup)?.viewTreeObserver
                if (vto != null && vto.isAlive) {
                    listener?.let { vto.removeOnGlobalLayoutListener(it) }
                    preDraw?.let { vto.removeOnPreDrawListener(it) }
                }
            }
            unobserve()
            (tv?.parent as? ViewGroup)?.removeView(tv)
        } catch (_: Throwable) {
        }
        view = null
        host = null
        listener = null
        preDraw = null
        observer = null
        searchIcon = null
        composer = null
    }

    private fun style() {
        val tv = view ?: return
        val a = host?.get() ?: return
        val on = state == "on" || state == "locked"
        tv.text = if (on) LABEL_ON else LABEL_OFF
        tv.setTextColor(if (on) Color.WHITE else 0xFFB5BAC1.toInt())
        val bg = GradientDrawable()
        bg.cornerRadius = dp(a, 12f).toFloat()
        if (on) {
            bg.setColor(0xFF23A55A.toInt())
        } else {
            bg.setColor(0x33808080)
            bg.setStroke(dp(a, 1f), 0xFF80848E.toInt())
        }
        tv.background = bg
        tv.alpha = if (state == "locked") 0.85f else 1f
    }

    // ------------------------------------------------------------------ placement

    /**
     * Runs before every frame is drawn. The button is glued to its anchor icon: it moves with it (a screen
     * sliding in or out) and disappears in the same frame the icon is gone. The heavier checks of [place]
     * run a few times a second.
     */
    private fun follow() {
        val tv = view ?: return
        val a = host?.get() ?: return
        if (state == "hide" || placing) return
        val decor = a.window.decorView as? ViewGroup ?: return
        val icon = searchIcon?.get()
        screenRect.set(0, 0, a.resources.displayMetrics.widthPixels, a.resources.displayMetrics.heightPixels)
        val ok = icon != null && isHeaderIcon(icon, screenRect, dp(a, MAX_ICON_DP.toFloat()), ICON_WORDS)
        if (!ok) {
            if (tv.visibility == View.VISIBLE) tv.visibility = View.INVISIBLE
        } else if (tv.visibility == View.VISIBLE) {
            position(tv, icon!!, decor, a)
        }
        if (SystemClock.uptimeMillis() - lastFull >= 150) place()
    }

    private fun position(tv: View, icon: View, decor: ViewGroup, a: Activity) {
        icon.getLocationOnScreen(tmpLoc)
        decor.getLocationOnScreen(tmpDecorLoc)
        val x = tmpLoc[0] - tmpDecorLoc[0] - dp(a, 4f) - tv.measuredWidth
        val y = tmpLoc[1] - tmpDecorLoc[1] + (icon.height - tv.measuredHeight) / 2
        if (tv.translationX != x.toFloat()) tv.translationX = x.toFloat()
        if (tv.translationY != y.toFloat()) tv.translationY = y.toFloat()
    }

    private fun place() {
        if (placing) return
        placing = true
        lastFull = SystemClock.uptimeMillis()
        try {
            val tv = view ?: return
            val a = host?.get() ?: return
            val decor = a.window.decorView as? ViewGroup ?: return
            val icon = findSearchIcon(a, decor)
            if (icon == null) {
                why = "hidden: no search icon on screen"
                tv.visibility = View.INVISIBLE
                return
            }
            // Only on the chat screen itself: not under Discord's full-screen image viewer (or any other
            // screen laid over the chat), and not on the search screen.
            if (isCovered(icon, decor, a)) {
                why = "hidden: a full-screen view covers the chat header"
                tv.visibility = View.INVISIBLE
                return
            }
            if (!hasComposer(decor, a)) {
                why = "hidden: no message box on screen (DM list or another screen)"
                tv.visibility = View.INVISIBLE
                return
            }
            if (headerHasTextInput(decor, a)) {
                why = "hidden: text input in the header (search screen)"
                tv.visibility = View.INVISIBLE
                return
            }
            why = "shown"
            tv.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            position(tv, icon, decor, a)
            if (tv.visibility != View.VISIBLE) tv.visibility = View.VISIBLE
            // Only when something was added after us: bringToFront() requests a layout, which would call place() again, forever.
            if (decor.getChildAt(decor.childCount - 1) !== tv) tv.bringToFront()
        } finally {
            placing = false
        }
    }

    /**
     * True when something drawn AFTER the icon's screen covers (nearly) the whole display with an opaque
     * background: Discord's image viewer, a full-screen dialog or another screen pushed over the chat.
     * Discord keeps the chat underneath in the view tree, so its search icon still looks "visible".
     * Views are drawn in child order, so only later siblings of the icon's ancestors can lie on top of it.
     */
    private fun isCovered(icon: View, decor: ViewGroup, a: Activity): Boolean {
        val w = a.resources.displayMetrics.widthPixels
        val h = a.resources.displayMetrics.heightPixels
        val ir = Rect()
        if (!icon.getGlobalVisibleRect(ir)) return false
        val cx = ir.centerX()
        val cy = ir.centerY()
        var child: View = icon
        var parent = child.parent as? ViewGroup
        while (parent != null) {
            val idx = parent.indexOfChild(child)
            for (i in idx + 1 until parent.childCount) {
                if (coversScreen(parent.getChildAt(i), w, h, cx, cy, 3)) return true
            }
            if (parent === decor) break
            child = parent
            parent = parent.parent as? ViewGroup
        }
        return false
    }

    private fun coversScreen(v: View, w: Int, h: Int, cx: Int, cy: Int, depth: Int): Boolean {
        if (v === view || v.visibility != View.VISIBLE || v.alpha < 0.5f) return false
        val bg = v.background
        if (bg != null && bg.opacity == PixelFormat.OPAQUE) {
            val r = Rect()
            // It must lie over the icon itself (the chat's own content area starts below the header) and be screen-sized.
            if (v.getGlobalVisibleRect(r) && r.contains(cx, cy) && r.width() * 10 >= w * 9 && r.height() * 10 >= h * 7) return true
        }
        if (depth > 0 && v is ViewGroup) {
            for (i in 0 until v.childCount) if (coversScreen(v.getChildAt(i), w, h, cx, cy, depth - 1)) return true
        }
        return false
    }

    /** The search screen has its input at the top; the chat screen's input is at the bottom. */
    private fun headerHasTextInput(root: ViewGroup, a: Activity): Boolean {
        val limit = dp(a, HEADER_DP.toFloat())
        fun scan(v: View, offY: Int): Boolean {
            if (v === view || v.visibility != View.VISIBLE) return false
            if (v is EditText && v.isShown) {
                val r = Rect()
                if (v.getGlobalVisibleRect(r) && r.top < limit) return true
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    val c = v.getChildAt(i)
                    val top = offY + c.top - v.scrollY
                    if (top > limit) continue
                    if (scan(c, top)) return true
                }
            }
            return false
        }
        return scan(root, 0)
    }

    /**
     * The view the button goes left of: the leftmost search, call or video icon on the same row as the
     * search icon. A DM has call and video icons next to the search icon; a server channel only has search.
     */
    /** Bottom edge (screen y) of the chat header: just under its search icon. -1 when no chat header is on screen. */
    fun headerBottom(a: Activity): Int {
        val decor = a.window.decorView as? ViewGroup ?: return -1
        val icon = findSearchIcon(a, decor) ?: return -1
        if (isCovered(icon, decor, a)) return -1
        val loc = IntArray(2)
        icon.getLocationOnScreen(loc)
        return loc[1] + icon.height + dp(a, 10f)
    }

    private fun findSearchIcon(a: Activity, decor: ViewGroup): View? {
        val screen = Rect(0, 0, a.resources.displayMetrics.widthPixels, a.resources.displayMetrics.heightPixels)
        val maxIcon = dp(a, MAX_ICON_DP.toFloat())
        val cached = searchIcon?.get()
        if (cached != null && isHeaderIcon(cached, screen, maxIcon, ICON_WORDS)) return cached
        val icons = ArrayList<View>()
        walk(decor, 0, dp(a, HEADER_DP.toFloat()), maxIcon, screen, icons)
        // Later siblings are drawn on top, so the LAST valid search icon is the one on screen.
        val search = icons.lastOrNull { (it.contentDescription ?: "").contains("search", ignoreCase = true) }
        if (search == null) {
            searchIcon = null
            return null
        }
        val sy = IntArray(2)
        search.getLocationOnScreen(sy)
        val rowMid = sy[1] + search.height / 2
        var best: View = search
        var bestX = sy[0]
        val loc = IntArray(2)
        for (v in icons) {
            v.getLocationOnScreen(loc)
            val mid = loc[1] + v.height / 2
            if (Math.abs(mid - rowMid) > search.height / 2 + 1) continue // another row
            if (loc[0] < bestX) {
                best = v
                bestX = loc[0]
            }
        }
        searchIcon = WeakReference(best)
        return best
    }

    /** A visible message box in the lower part of the screen: the chat screen has one, the DM list does not. */
    private fun hasComposer(decor: ViewGroup, a: Activity): Boolean {
        val h = a.resources.displayMetrics.heightPixels
        fun ok(v: View?): Boolean {
            if (v == null || !v.isAttachedToWindow || !v.isShown) return false
            val r = Rect()
            return v.getGlobalVisibleRect(r) && r.top > h * 35 / 100
        }
        if (ok(composer?.get())) return true
        val now = System.currentTimeMillis()
        if (now - composerScanAt < 150) return false // a miss is not re-scanned on every layout pass
        composerScanAt = now
        val found = arrayOfNulls<View>(1)
        fun scan(v: View) {
            if (found[0] != null || v === view || v.visibility != View.VISIBLE) return
            if (v is EditText && ok(v)) {
                found[0] = v
                return
            }
            if (v is ViewGroup) for (i in v.childCount - 1 downTo 0) scan(v.getChildAt(i))
        }
        scan(decor)
        composer = found[0]?.let { WeakReference(it) }
        return found[0] != null
    }

    /**
     * A real, currently visible search icon. Screens that were left (earlier chats, the settings screen)
     * can stay in the view tree, hidden behind the new one or moved off to the side; their icons must not
     * count, or the button follows one of them and "disappears".
     */
    private fun isHeaderIcon(v: View, screen: Rect, maxIcon: Int, words: List<String>): Boolean {
        if (!v.isAttachedToWindow || !v.isShown || v.alpha <= 0.01f) return false
        val d = v.contentDescription
        if (d == null || words.none { d.contains(it, ignoreCase = true) }) return false
        if (v.width !in 1..maxIcon || v.height !in 1..maxIcon) return false
        val r = Rect()
        if (!v.getGlobalVisibleRect(r) || r.isEmpty) return false
        // Mostly inside the screen (a screen sliding in or out is half off it).
        val inside = Rect(r)
        if (!inside.intersect(screen)) return false
        return inside.width() * 2 >= v.width && inside.height() * 2 >= v.height
    }

    /** Depth-first over the top of the screen only; collects every header icon (in drawing order). */
    private fun walk(v: View, offY: Int, limit: Int, maxIcon: Int, screen: Rect, found: ArrayList<View>) {
        if (v === view || v.visibility != View.VISIBLE) return
        if (isHeaderIcon(v, screen, maxIcon, ICON_WORDS)) found.add(v)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val c = v.getChildAt(i)
                val top = offY + c.top - v.scrollY
                if (top > limit) continue
                walk(c, top, limit, maxIcon, screen, found)
            }
        }
    }
}
