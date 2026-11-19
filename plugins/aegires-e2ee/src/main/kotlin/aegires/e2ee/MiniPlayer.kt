package aegires.e2ee

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.VideoView
import java.io.File
import java.lang.ref.WeakReference

/**
 * The "Video only" choice of the picture-in-picture button: a small player that floats over the
 * app while you keep using Discord. It is a view of its own inside Discord's window, so nothing else
 * minimizes. (Android's real picture-in-picture can only shrink the whole app window, which is what
 * the "Whole app" choice does; see [PipPlayer].)
 *
 * Drag it anywhere, it settles against the nearest side when let go. A tap shows ✕, ⤢ (back to full
 * screen) and ⏸ / ▶ for two seconds. It stays while you move between chats and ends with the video.
 */
internal object MiniPlayer {
    private const val CONTROLS_MS = 2000L

    private var box: FrameLayout? = null
    private var video: VideoView? = null
    private var host: WeakReference<Activity>? = null

    fun active(): Boolean = box != null

    /** True when the mini player started and the video continues in it. */
    fun show(a: Activity, path: String, title: String, startMs: Int, speed: Float, vw: Int, vh: Int): Boolean {
        if (!VideoPlayer.allowed(path) || !File(path).isFile) return false
        val decor = a.window.decorView as? ViewGroup ?: return false
        InlineVideo.close()
        InlineAudio.close()
        close()
        host = WeakReference(a)
        fun d(v: Float): Int = InlineVideo.dp(a, v)

        // Half the screen wide, in the video's own shape, and never taller than 40% of the screen.
        val dm = a.resources.displayMetrics
        val ratio = if (vw > 0 && vh > 0) (vw.toFloat() / vh).coerceIn(0.4f, 2.4f) else 16f / 9f
        var w = Math.round(dm.widthPixels * 0.5f)
        var h = Math.round(w / ratio)
        val maxH = Math.round(dm.heightPixels * 0.4f)
        if (h > maxH) {
            h = maxH
            w = Math.round(h * ratio)
        }

        val root = FrameLayout(a)
        root.setBackgroundColor(Color.BLACK)
        root.isClickable = true
        root.elevation = d(8f).toFloat()

        val vv = VideoView(a)
        vv.setVideoPath(path)
        vv.setOnPreparedListener { mp ->
            if (startMs > 0) vv.seekTo(startMs)
            if (speed != 1f) PlayerUi.applySpeed(mp, speed, true)
            vv.start()
        }
        vv.setOnCompletionListener { close() }
        vv.setOnErrorListener { _, _, _ ->
            close()
            android.widget.Toast.makeText(a, "Could not play the video", android.widget.Toast.LENGTH_SHORT).show()
            true
        }
        root.addView(vv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))

        // The controls: hidden after two seconds, a tap on the video brings them back.
        val ctl = FrameLayout(a)
        val hideCtl = Runnable { ctl.visibility = View.GONE }
        val showCtl = {
            ctl.visibility = View.VISIBLE
            ctl.removeCallbacks(hideCtl)
            ctl.postDelayed(hideCtl, CONTROLS_MS)
        }
        fun corner(g: Int): FrameLayout.LayoutParams {
            val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, g)
            lp.setMargins(d(6f), d(6f), d(6f), d(6f))
            return lp
        }
        ctl.addView(PlayerUi.pill(a, "\u2715") { close() }, corner(Gravity.TOP or Gravity.END))
        ctl.addView(
            PlayerUi.pill(a, "\u26F6") {
                val pos = try { vv.currentPosition } catch (_: Throwable) { 0 }
                close()
                VideoPlayer.show(a, path, title, pos)
            },
            corner(Gravity.TOP or Gravity.START),
        )
        val playPause: TextView = PlayerUi.pill(a, "\u23F8") {
            if (vv.isPlaying) vv.pause() else vv.start()
            showCtl()
        }
        ctl.addView(playPause, corner(Gravity.CENTER))
        root.addView(ctl, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Drag it; a tap without moving shows the controls.
        val slop = ViewConfiguration.get(a).scaledTouchSlop
        val margin = d(10f).toFloat()
        var downX = 0f
        var downY = 0f
        var startX = 0f
        var startY = 0f
        var dragging = false
        fun maxX(): Float = Math.max(0, decor.width - w).toFloat()
        fun maxY(): Float = Math.max(0, decor.height - h).toFloat()
        root.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = root.translationX
                    startY = root.translationY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (Math.abs(dx) > slop || Math.abs(dy) > slop)) dragging = true
                    if (dragging) {
                        root.translationX = (startX + dx).coerceIn(0f, maxX())
                        root.translationY = (startY + dy).coerceIn(0f, maxY())
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        if (ctl.visibility == View.VISIBLE) {
                            ctl.removeCallbacks(hideCtl)
                            hideCtl.run()
                        } else {
                            playPause.text = if (vv.isPlaying) "\u23F8" else "\u25B6"
                            showCtl()
                        }
                    } else {
                        // Settle against the nearest side.
                        val left = root.translationX + w / 2f < decor.width / 2f
                        val x = if (left) margin else maxX() - margin
                        root.animate().translationX(x.coerceIn(0f, maxX())).setDuration(150L).start()
                    }
                }
            }
            true
        }

        // Bottom right, above the message box.
        decor.addView(root, FrameLayout.LayoutParams(w, h, Gravity.TOP or Gravity.START))
        root.translationX = Math.max(0f, (dm.widthPixels - w).toFloat() - margin)
        root.translationY = Math.max(0f, (dm.heightPixels - h).toFloat() - d(110f))
        box = root
        video = vv
        showCtl()
        return true
    }

    fun close() {
        val b = box
        val v = video
        box = null
        video = null
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
}
