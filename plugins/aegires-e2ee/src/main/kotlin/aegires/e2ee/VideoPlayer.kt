package aegires.e2ee

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.media.MediaPlayer
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import java.io.File

/**
 * Full-screen player for a decrypted video or audio file (audio shows a note symbol and the title
 * on the black screen; the same transport controls apply). Discord's own inline player only streams over
 * http(s), so it cannot play the file:// copies we write; Android's VideoView reads the file
 * directly. Only files under an "aegires" cache folder are accepted.
 */
internal object VideoPlayer {
    private var current: Dialog? = null

    fun allowed(path: String): Boolean = path.startsWith("/") && path.contains("/aegires/") && !path.contains("..")

    private val AUDIO = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "amr", "3ga", "mka")

    fun isAudio(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in AUDIO

    fun show(activity: Activity, path: String, title: String = "", startMs: Int = 0) {
        activity.runOnUiThread {
            try {
                if (!allowed(path) || !File(path).isFile) {
                    Toast.makeText(activity, "Media file not found", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                current?.dismiss()
                MiniPlayer.close()
                val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                val root = FrameLayout(activity)
                root.setBackgroundColor(Color.BLACK)

                val video = VideoView(activity)
                var mpRef: MediaPlayer? = null
                var controls: PlayerControls? = null
                val transport = object : Transport {
                    override val playing: Boolean get() = try { video.isPlaying } catch (_: Throwable) { false }
                    override val position: Int get() = try { video.currentPosition } catch (_: Throwable) { 0 }
                    override val duration: Int get() = try { video.duration } catch (_: Throwable) { 0 }
                    override fun play() = video.start()
                    override fun pause() = video.pause()
                    override fun seekTo(ms: Int) = video.seekTo(ms)
                    override fun setSpeed(speed: Float): Boolean = PlayerUi.applySpeed(mpRef, speed, playing)
                }
                video.setVideoPath(path)
                video.setOnPreparedListener { mp ->
                    mpRef = mp
                    if (startMs > 0) video.seekTo(startMs)
                    video.start()
                    controls?.applyCurrentSpeed()
                }
                video.setOnCompletionListener { controls?.poke() }
                video.setOnErrorListener { _, _, _ ->
                    Toast.makeText(activity, "Can't play this file format", Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                    true
                }
                root.addView(
                    video,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER),
                )

                if (isAudio(path)) {
                    val label = TextView(activity)
                    label.text = "\u266A\n" + title.take(80)
                    label.setTextColor(Color.WHITE)
                    label.textSize = 22f
                    label.gravity = Gravity.CENTER
                    label.setPadding(48, 0, 48, 0)
                    root.addView(
                        label,
                        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
                    )
                }

                // Same controls as the in-chat player; picture-in-picture is for video only.
                val pip: ((Boolean) -> Unit)? = if (isAudio(path)) null else {
                    { whole ->
                        val pos = video.currentPosition
                        val sp = controls?.speed ?: 1f
                        val m = mpRef
                        val w = m?.videoWidth ?: 0
                        val h = m?.videoHeight ?: 0
                        val entered = if (whole) PipPlayer.enterApp(activity, w, h) else MiniPlayer.show(activity, path, title, pos, sp, w, h)
                        if (entered) dialog.dismiss()
                        else Toast.makeText(activity, "Picture-in-picture is not available here", Toast.LENGTH_SHORT).show()
                    }
                }
                val ctl = PlayerControls(activity, transport, true, onClose = { dialog.dismiss() }, onFullscreen = null, onPip = pip)
                controls = ctl
                root.addView(ctl.layer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                ctl.attachTo(root)
                ctl.poke()
                root.postOnAnimation(object : Runnable {
                    override fun run() {
                        if (current !== dialog) return
                        ctl.tick()
                        root.postOnAnimation(this)
                    }
                })

                dialog.setContentView(root)
                dialog.setOnDismissListener {
                    try {
                        video.stopPlayback()
                    } catch (_: Throwable) {
                    }
                    if (current === dialog) current = null
                }
                dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                current = dialog
                dialog.show()
            } catch (_: Throwable) {
                Toast.makeText(activity, "Can't open the video player", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun close() {
        try {
            current?.dismiss()
        } catch (_: Throwable) {
        }
        current = null
    }
}
