package aegires.e2ee

import android.app.Activity
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import java.io.File
import java.lang.ref.WeakReference

/**
 * Plays a decrypted video IN the chat, on top of its own picture, instead of opening the full-screen
 * viewer. Discord draws the chat natively and cannot play file:// video, so this puts a small native
 * player view over the picture and keeps it glued to that picture while the chat scrolls.
 *
 * How the picture is found: the spot of the last finger-down ([TouchSpy]) is looked up in the view
 * tree; the deepest view there that is picture-sized is the target. If that fails (no recent touch, no
 * suitable view, a playback error) the full-screen [VideoPlayer] is used, so a tap always plays.
 *
 * The player: tap = pause / play, a seek bar, a button for full screen (continues at the same
 * position) and a close button. It stops by itself when its picture is scrolled away, replaced by
 * another message (the chat re-uses views), or when the video ends.
 */
internal object InlineVideo {
    private const val MIN_DP = 100 // smaller than this is not a picture
    private const val TOUCH_MAX_AGE_MS = 1500L
    private const val CONTROLS_MS = 2000L // the controls hide themselves after this long
    private const val VIEW_BUDGET = 20000 // most views looked at for one tap

    private var box: FrameLayout? = null
    private var video: VideoView? = null
    private var seek: SeekBar? = null
    private var host: WeakReference<Activity>? = null
    private var target: WeakReference<View>? = null
    private var path = ""
    private var title = ""
    private var targetW = 0
    private var targetH = 0
    private var hiddenSince = 0L
    private var dragging = false
    private var taps = 0
    private var lastTapAt = 0L
    private var pendingToggle: Runnable? = null

    /** What happened on the last start: "inline", or why it fell back to the full-screen player. */
    @Volatile private var status = "no tap yet"

    fun status(): String = status

    /** Sets the status text before a play starts, so an old result is never shown for a new tap. */
    fun note(text: String) {
        status = text
    }

    /** [vw] x [vh] is the picture's pixel size (0 = unknown); its shape picks the right view. */
    fun start(activity: Activity, filePath: String, fileTitle: String, vw: Int, vh: Int) {
        runUi(activity) {
            try {
                InlineAudio.close()
                close()
                if (!VideoPlayer.allowed(filePath) || !File(filePath).isFile) {
                    status = "full screen: file not found"
                    VideoPlayer.show(activity, filePath, fileTitle)
                    return@runUi
                }
                val view = pictureAtLastTouch(activity, vw, vh)
                if (view == null) {
                    VideoPlayer.show(activity, filePath, fileTitle)
                    return@runUi
                }
                show(activity, view, filePath, fileTitle)
                status = "inline on " + view.javaClass.name + " " + targetW + "x" + targetH
            } catch (t: Throwable) {
                close()
                status = "full screen: error " + t.javaClass.simpleName + " " + (t.message ?: "").take(60)
                VideoPlayer.show(activity, filePath, fileTitle)
            }
        }
    }

    fun close() {
        val b = box
        val v = video
        pendingToggle?.let { b?.removeCallbacks(it) }
        pendingToggle = null
        box = null
        video = null
        seek = null
        target = null
        host = null
        try {
            v?.stopPlayback()
        } catch (_: Throwable) {
        }
        try {
            (b?.parent as? ViewGroup)?.removeView(b)
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------ finding the picture

    internal fun dp(a: Activity, v: Float): Int = (v * a.resources.displayMetrics.density + 0.5f).toInt()

    /** [minDp]: the smallest side a picture can have (an audio card is much flatter than a video). */
    internal fun pictureAtLastTouch(a: Activity, vw: Int, vh: Int, minDp: Int = MIN_DP): View? {
        if (!TouchSpy.recent(TOUCH_MAX_AGE_MS)) {
            status = "full screen: no recent touch known (" + TouchSpy.describe() + ")"
            return null
        }
        val decor = a.window.decorView as? ViewGroup
        if (decor == null) {
            status = "full screen: no window"
            return null
        }
        val origin = IntArray(2)
        decor.getLocationOnScreen(origin)
        val x = TouchSpy.x - origin[0]
        val y = TouchSpy.y - origin[1]
        // Every view under the finger, whatever its depth and whatever is drawn on top of it. (A
        // transparent screen-sized view above the chat used to end the search before it reached
        // the picture.)
        val hits = ArrayList<View>()
        viewsAt(decor, x, y, hits, intArrayOf(VIEW_BUDGET))
        if (hits.isEmpty()) {
            status = "full screen: no view at " + x.toInt() + "," + y.toInt()
            return null
        }
        val min = dp(a, minDp.toFloat())
        val sw = a.resources.displayMetrics.widthPixels
        val sh = a.resources.displayMetrics.heightPixels
        val want = if (vw > 0 && vh > 0) vw.toFloat() / vh else 0f
        // The picture is the smallest view that is big enough, not screen-sized, and (when the
        // video's shape is known) has about that shape.
        var best: View? = null
        var bestArea = Float.MAX_VALUE
        var providers = 0
        val seen = ArrayList<String>()
        for (v in hits) {
            val provider = try { v.accessibilityNodeProvider } catch (_: Throwable) { null }
            if (provider != null) providers++
            val wr = winRect(v)
            val rw = wr.width()
            val rh = wr.height()
            val bigEnough = rw >= min && rh >= min
            if (bigEnough && seen.size < 10) {
                seen.add(v.javaClass.name + " " + rw.toInt() + "x" + rh.toInt() + (if (v.scaleY < 0f) " flipped" else ""))
            }
            val screenSized = rw * 100 >= sw * 95 && rh * 10 >= sh * 7
            if (!bigEnough || screenSized) continue
            val ratio = rw / rh
            val shapeOk = want == 0f || Math.abs(ratio - want) <= want * 0.18f
            if (shapeOk && rw * rh < bestArea) {
                best = v
                bestArea = rw * rh
            }
        }
        if (best != null) return best
        val shape = if (want > 0f) "wanted ratio " + String.format(java.util.Locale.ROOT, "%.2f", want) else "shape unknown"
        status = "full screen: no picture-shaped view among " + hits.size + " under the finger at " + x.toInt() + "," + y.toInt() +
            " (" + shape + ", " + providers + " with accessibility nodes): " + seen.joinToString("; ")
        return null
    }

    /**
     * Where a view is in the window, transforms included. The chat list is inverted (flipped), so a
     * row's "top" and "left + width" no longer describe where it is drawn; the corners are pushed
     * through every ancestor's matrix instead.
     */
    internal fun winRect(v: View): RectF {
        val pts = floatArrayOf(0f, 0f, v.width.toFloat(), 0f, v.width.toFloat(), v.height.toFloat(), 0f, v.height.toFloat())
        var cur: View = v
        var depth = 0
        while (depth < 40) {
            if (!cur.matrix.isIdentity) cur.matrix.mapPoints(pts)
            val parent = cur.parent as? View
            val dx = cur.left - (parent?.scrollX ?: 0)
            val dy = cur.top - (parent?.scrollY ?: 0)
            var i = 0
            while (i < pts.size) {
                pts[i] += dx
                pts[i + 1] += dy
                i += 2
            }
            if (parent == null) break
            cur = parent
            depth++
        }
        var l = pts[0]
        var t = pts[1]
        var rr = pts[0]
        var bb = pts[1]
        var i = 2
        while (i < pts.size) {
            l = Math.min(l, pts[i]); rr = Math.max(rr, pts[i])
            t = Math.min(t, pts[i + 1]); bb = Math.max(bb, pts[i + 1])
            i += 2
        }
        return RectF(l, t, rr, bb)
    }

    /**
     * Adds every visible view under a point (window coordinates) to [out], the view itself first and
     * its children after, last child (drawn on top) first. A view with no size of its own is looked
     * through anyway, since its children can still be drawn elsewhere.
     */
    private fun viewsAt(v: View, x: Float, y: Float, out: ArrayList<View>, budget: IntArray) {
        if (budget[0] <= 0) return
        budget[0]--
        if (v.visibility != View.VISIBLE || v === box || InlineAudio.owns(v)) return
        val r = winRect(v)
        val has = !r.isEmpty
        if (has && !r.contains(x, y)) return
        if (has) out.add(v)
        if (v is ViewGroup) {
            for (i in v.childCount - 1 downTo 0) viewsAt(v.getChildAt(i), x, y, out, budget)
        }
    }

    // ------------------------------------------------------------------ the player view

    private fun show(a: Activity, pic: View, filePath: String, fileTitle: String) {
        val decor = a.window.decorView as? ViewGroup ?: throw IllegalStateException("no decor")
        path = filePath
        title = fileTitle
        target = WeakReference(pic)
        host = WeakReference(a)
        val rect0 = winRect(pic)
        targetW = Math.round(rect0.width())
        targetH = Math.round(rect0.height())
        hiddenSince = 0L
        dragging = false

        val root = FrameLayout(a)
        root.setBackgroundColor(Color.BLACK)
        root.isClickable = true

        val vv = VideoView(a)
        vv.setVideoPath(filePath)
        vv.setOnPreparedListener { mp ->
            mp.isLooping = false
            vv.start()
            seek?.max = vv.duration.coerceAtLeast(1)
        }
        vv.setOnCompletionListener { close() } // back to the picture
        vv.setOnErrorListener { _, _, _ ->
            val act = host?.get()
            close()
            if (act != null) VideoPlayer.show(act, filePath, fileTitle)
            true
        }
        root.addView(vv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        // Everything the user can press lives in one layer ("ctl") that hides itself 2 seconds after it
        // appears. One tap on the video shows it again (or hides it at once); three quick taps go to
        // full screen. The first tap (the one that started the player) counts as the first of three.
        val ctl = FrameLayout(a)
        val hint = TextView(a)
        val hideCtl = Runnable {
            ctl.visibility = View.GONE
            hint.visibility = View.GONE // the hint is only for the first time
        }
        val showCtl = {
            ctl.visibility = View.VISIBLE
            ctl.removeCallbacks(hideCtl)
            ctl.postDelayed(hideCtl, CONTROLS_MS)
        }
        taps = 1
        lastTapAt = SystemClock.uptimeMillis()
        root.setOnClickListener {
            val now = SystemClock.uptimeMillis()
            taps = if (now - lastTapAt <= 600L) taps + 1 else 1
            lastTapAt = now
            pendingToggle?.let { root.removeCallbacks(it) }
            pendingToggle = null
            if (taps >= 3) {
                taps = 0
                val pos = vv.currentPosition
                val act = host?.get()
                close()
                if (act != null) VideoPlayer.show(act, filePath, fileTitle, pos)
            } else {
                // One tap shows or hides the controls, but only once it is clear no third tap follows.
                val toggle = Runnable {
                    if (ctl.visibility == View.VISIBLE) {
                        ctl.removeCallbacks(hideCtl)
                        hideCtl.run()
                    } else {
                        showCtl()
                    }
                }
                pendingToggle = toggle
                root.postDelayed(toggle, 350L)
            }
        }
        root.addView(ctl, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val bar = SeekBar(a)
        bar.setPadding(dp(a, 6f), 0, dp(a, 6f), 0)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) vv.seekTo(p)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {
                dragging = true
                ctl.removeCallbacks(hideCtl) // stay while the finger is on the bar
            }

            override fun onStopTrackingTouch(s: SeekBar?) {
                dragging = false
                showCtl()
            }
        })
        ctl.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 28f), Gravity.BOTTOM))

        hint.text = "Tap 3 times quickly for full screen"
        hint.setTextColor(Color.WHITE)
        hint.textSize = 11f
        hint.setPadding(dp(a, 8f), dp(a, 3f), dp(a, 8f), dp(a, 3f))
        val hintBg = GradientDrawable()
        hintBg.cornerRadius = dp(a, 10f).toFloat()
        hintBg.setColor(0xAA000000.toInt())
        hint.background = hintBg
        val hlp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        hlp.bottomMargin = dp(a, 34f)
        ctl.addView(hint, hlp)

        ctl.addView(pill(a, "\u2715") { close() }, corner(a, Gravity.TOP or Gravity.END))
        ctl.addView(
            pill(a, "\u26F6") {
                val pos = vv.currentPosition
                val act = host?.get()
                close()
                if (act != null) VideoPlayer.show(act, filePath, fileTitle, pos)
            },
            corner(a, Gravity.TOP or Gravity.START),
        )
        val playPause = pill(a, "\u23F8") {
            if (vv.isPlaying) vv.pause() else vv.start()
            showCtl()
        }
        ctl.addView(playPause, corner(a, Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        decor.addView(root, FrameLayout.LayoutParams(targetW, targetH, Gravity.TOP or Gravity.START))
        box = root
        video = vv
        seek = bar
        follow(root, pic, decor)
        showCtl()
        root.postOnAnimation(object : Runnable {
            override fun run() {
                if (box !== root) return
                if (!follow(root, pic, decor)) return
                if (!dragging) seek?.progress = vv.currentPosition
                val symbol = if (vv.isPlaying) "\u23F8" else "\u25B6"
                if (playPause.text.toString() != symbol) playPause.text = symbol
                root.postOnAnimation(this)
            }
        })
    }

    private fun corner(a: Activity, gravity: Int): FrameLayout.LayoutParams {
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, gravity)
        lp.setMargins(dp(a, 6f), dp(a, 6f), dp(a, 6f), dp(a, 6f))
        return lp
    }

    private fun pill(a: Activity, label: String, onTap: () -> Unit): TextView {
        val t = TextView(a)
        t.text = label
        t.setTextColor(Color.WHITE)
        t.textSize = 14f
        t.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(a, 10f), dp(a, 4f), dp(a, 10f), dp(a, 4f))
        val bg = GradientDrawable()
        bg.cornerRadius = dp(a, 14f).toFloat()
        bg.setColor(0xAA000000.toInt())
        t.background = bg
        t.setOnClickListener { onTap() }
        return t
    }

    /**
     * Keeps the player on its picture. Returns false (after closing) when the picture is gone, was
     * re-used for something else, or has been scrolled out of sight for a while.
     */
    private fun follow(root: FrameLayout, pic: View, decor: ViewGroup): Boolean {
        val vv = video
        if (!pic.isAttachedToWindow) {
            close()
            return false
        }
        val full = winRect(pic)
        if (Math.abs(Math.round(full.width()) - targetW) > 2 || Math.abs(Math.round(full.height()) - targetH) > 2) {
            close() // the chat re-used this view for something else
            return false
        }
        val vis = Rect()
        val seen = pic.isShown && pic.getGlobalVisibleRect(vis) && vis.height() * 10 >= full.height() * 3
        if (!seen) {
            // Scrolled away (or covered by another screen): pause and hide; give up after a while.
            root.visibility = View.INVISIBLE
            if (vv != null && vv.isPlaying) vv.pause()
            val now = System.currentTimeMillis()
            if (hiddenSince == 0L) hiddenSince = now
            if (now - hiddenSince > 4000L) {
                close()
                return false
            }
            return true
        }
        hiddenSince = 0L
        root.visibility = View.VISIBLE
        root.translationX = full.left
        root.translationY = full.top
        // Only the part of the picture that is on screen (not under the header or the message box).
        root.clipBounds = Rect(
            Math.round(vis.left - full.left), Math.round(vis.top - full.top),
            Math.round(vis.right - full.left), Math.round(vis.bottom - full.top),
        )
        return true
    }

    internal fun runUi(a: Activity, block: () -> Unit) {
        if (android.os.Looper.myLooper() === android.os.Looper.getMainLooper()) block() else a.runOnUiThread { block() }
    }
}
