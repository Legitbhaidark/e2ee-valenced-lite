package aegires.e2ee

import android.app.Activity
import android.graphics.Color
import android.graphics.Rect
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

    /** [vw] x [vh] is the picture's pixel size (0 = unknown); its shape picks the right view. */
    fun start(activity: Activity, filePath: String, fileTitle: String, vw: Int, vh: Int) {
        runUi(activity) {
            try {
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
                status = "inline on " + view.javaClass.simpleName + " " + view.width + "x" + view.height
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

    private fun dp(a: Activity, v: Float): Int = (v * a.resources.displayMetrics.density + 0.5f).toInt()

    private fun pictureAtLastTouch(a: Activity, vw: Int, vh: Int): View? {
        if (!TouchSpy.recent(TOUCH_MAX_AGE_MS)) {
            status = "full screen: no recent touch known (" + TouchSpy.describe() + ")"
            return null
        }
        val decor = a.window.decorView as? ViewGroup
        if (decor == null) {
            status = "full screen: no window"
            return null
        }
        val x = TouchSpy.x.toInt()
        val y = TouchSpy.y.toInt()
        val deepest = deepestAt(decor, x, y)
        if (deepest == null) {
            status = "full screen: no view at " + x + "," + y
            return null
        }
        val min = dp(a, MIN_DP.toFloat())
        val sw = a.resources.displayMetrics.widthPixels
        val sh = a.resources.displayMetrics.heightPixels
        val want = if (vw > 0 && vh > 0) vw.toFloat() / vh else 0f
        // The views under the finger, from the deepest outwards. The picture is the first one that is
        // big enough, not screen-sized, and (when the video's shape is known) has about that shape.
        val chain = ArrayList<String>()
        var v: View? = deepest
        var depth = 0
        while (v != null && depth < 8) {
            chain.add(v.javaClass.simpleName + " " + v.width + "x" + v.height)
            val bigEnough = v.width >= min && v.height >= min
            val screenSized = v.width * 100 >= sw * 95 && v.height * 10 >= sh * 7
            if (bigEnough && !screenSized) {
                val ratio = v.width.toFloat() / v.height
                val shapeOk = want == 0f || Math.abs(ratio - want) <= want * 0.18f
                if (shapeOk) return v
            }
            v = v.parent as? View
            depth++
        }
        val shape = if (want > 0f) "wanted ratio " + String.format(java.util.Locale.ROOT, "%.2f", want) else "shape unknown"
        status = "full screen: no view with the picture's shape (" + shape + "): " + chain.take(5).joinToString("; ")
        return null
    }

    /** The deepest visible view under a screen point; later siblings are drawn on top, so they are tried first. */
    private fun deepestAt(v: View, x: Int, y: Int): View? {
        if (v.visibility != View.VISIBLE || v === box) return null
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        if (x < loc[0] || y < loc[1] || x >= loc[0] + v.width || y >= loc[1] + v.height) return null
        if (v is ViewGroup) {
            for (i in v.childCount - 1 downTo 0) {
                val c = v.getChildAt(i)
                val d = deepestAt(c, x, y)
                if (d != null) return d
            }
        }
        return v
    }

    // ------------------------------------------------------------------ the player view

    private fun show(a: Activity, pic: View, filePath: String, fileTitle: String) {
        val decor = a.window.decorView as? ViewGroup ?: throw IllegalStateException("no decor")
        path = filePath
        title = fileTitle
        target = WeakReference(pic)
        host = WeakReference(a)
        targetW = pic.width
        targetH = pic.height
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
        // The first tap (the one that started the player) counts too: tap three times quickly for full screen.
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
                // A single tap pauses / resumes, but only once it is clear no third tap follows.
                val toggle = Runnable { if (vv.isPlaying) vv.pause() else vv.start() }
                pendingToggle = toggle
                root.postDelayed(toggle, 350L)
            }
        }

        val bar = SeekBar(a)
        bar.setPadding(dp(a, 6f), 0, dp(a, 6f), 0)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) vv.seekTo(p)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {
                dragging = true
            }

            override fun onStopTrackingTouch(s: SeekBar?) {
                dragging = false
            }
        })
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 28f), Gravity.BOTTOM))

        // A short hint at the bottom, gone after a few seconds.
        val hint = TextView(a)
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
        root.addView(hint, hlp)
        hint.postDelayed({ hint.visibility = View.GONE }, 3500L)

        root.addView(pill(a, "✕") { close() }, corner(a, Gravity.TOP or Gravity.END))
        root.addView(
            pill(a, "⛶") {
                val pos = vv.currentPosition
                val act = host?.get()
                close()
                if (act != null) VideoPlayer.show(act, filePath, fileTitle, pos)
            },
            corner(a, Gravity.TOP or Gravity.START),
        )

        decor.addView(root, FrameLayout.LayoutParams(pic.width, pic.height, Gravity.TOP or Gravity.START))
        box = root
        video = vv
        seek = bar
        follow(root, pic, decor)
        root.postOnAnimation(object : Runnable {
            override fun run() {
                if (box !== root) return
                if (!follow(root, pic, decor)) return
                if (!dragging) seek?.progress = vv.currentPosition
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
        if (!pic.isAttachedToWindow || pic.width != targetW || pic.height != targetH) {
            close()
            return false
        }
        val full = IntArray(2)
        val decorLoc = IntArray(2)
        pic.getLocationOnScreen(full)
        decor.getLocationOnScreen(decorLoc)
        val vis = Rect()
        val seen = pic.isShown && pic.getGlobalVisibleRect(vis) && vis.height() * 10 >= pic.height * 3
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
        root.translationX = (full[0] - decorLoc[0]).toFloat()
        root.translationY = (full[1] - decorLoc[1]).toFloat()
        // Only the part of the picture that is on screen (not under the header or the message box).
        root.clipBounds = Rect(vis.left - full[0], vis.top - full[1], vis.right - full[0], vis.bottom - full[1])
        return true
    }

    private fun runUi(a: Activity, block: () -> Unit) {
        if (android.os.Looper.myLooper() === android.os.Looper.getMainLooper()) block() else a.runOnUiThread { block() }
    }
}
