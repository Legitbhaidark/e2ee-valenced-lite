package aegires.e2ee

import android.app.Activity
import android.app.PictureInPictureParams
import android.graphics.Color
import android.os.Build
import android.util.Rational
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.VideoView
import java.io.File

/**
 * Picture-in-picture for a decrypted video. Discord is one activity, so Android can only shrink the
 * whole app window: a black layer with just the video is put over everything and the window is sent
 * to PiP. If the host app does not allow PiP (or the phone does not), [enter] returns false and
 * nothing changes. The layer goes away when the window leaves PiP.
 */
internal object PipPlayer {
    private var layer: FrameLayout? = null
    private var video: VideoView? = null
    private var started = 0L
    private var wasPip = false

    fun supported(a: Activity): Boolean =
        Build.VERSION.SDK_INT >= 26 && a.packageManager.hasSystemFeature("android.software.picture_in_picture")

    /** True when the window went into picture-in-picture and the video continues there. */
    fun enter(a: Activity, path: String, startMs: Int, speed: Float, vw: Int, vh: Int): Boolean {
        if (!supported(a) || !VideoPlayer.allowed(path) || !File(path).isFile) return false
        close()
        val decor = a.window.decorView as? ViewGroup ?: return false
        val root = FrameLayout(a)
        root.setBackgroundColor(Color.BLACK)
        root.isClickable = true
        val vv = VideoView(a)
        vv.setVideoPath(path)
        vv.setOnPreparedListener { mp ->
            if (startMs > 0) vv.seekTo(startMs)
            if (speed != 1f) PlayerUi.applySpeed(mp, speed, true)
            vv.start()
        }
        vv.setOnCompletionListener { close() }
        vv.setOnErrorListener { _, _, _ -> close(); true }
        root.setOnClickListener { if (vv.isPlaying) vv.pause() else vv.start() }
        root.addView(vv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        decor.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        layer = root
        video = vv
        started = System.currentTimeMillis()
        wasPip = false
        return try {
            val b = PictureInPictureParams.Builder()
            if (vw > 0 && vh > 0) {
                // Android only accepts shapes between 1:2.39 and 2.39:1.
                val r = (vw.toFloat() / vh).coerceIn(0.43f, 2.38f)
                b.setAspectRatio(Rational((r * 1000).toInt(), 1000))
            }
            if (!a.enterPictureInPictureMode(b.build())) throw IllegalStateException("refused")
            root.postDelayed(object : Runnable {
                override fun run() {
                    if (layer !== root) return
                    val now = a.isInPictureInPictureMode
                    if (now) wasPip = true
                    // Left PiP (closed or expanded), or it never started: drop the layer and stop.
                    if ((wasPip && !now) || (!wasPip && System.currentTimeMillis() - started > 3000L)) {
                        close()
                        return
                    }
                    root.postDelayed(this, 300L)
                }
            }, 300L)
            true
        } catch (_: Throwable) {
            close()
            false
        }
    }

    /** Whole-app picture-in-picture: the app window itself shrinks, nothing is put over it. */
    fun enterApp(a: Activity, vw: Int, vh: Int): Boolean {
        if (!supported(a)) return false
        return try {
            val b = PictureInPictureParams.Builder()
            if (vw > 0 && vh > 0) {
                val r = (vw.toFloat() / vh).coerceIn(0.43f, 2.38f)
                b.setAspectRatio(Rational((r * 1000).toInt(), 1000))
            }
            a.enterPictureInPictureMode(b.build())
        } catch (_: Throwable) {
            false
        }
    }

    fun close() {
        val l = layer
        val v = video
        layer = null
        video = null
        try {
            v?.stopPlayback()
        } catch (_: Throwable) {
        }
        try {
            (l?.parent as? ViewGroup)?.removeView(l)
        } catch (_: Throwable) {
        }
    }
}
