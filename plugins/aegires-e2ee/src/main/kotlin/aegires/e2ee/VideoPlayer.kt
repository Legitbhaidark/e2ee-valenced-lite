package aegires.e2ee

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import java.io.File

/**
 * Full-screen player for a decrypted video file. Discord's own inline player only streams over
 * http(s), so it cannot play the file:// copies we write; Android's VideoView reads the file
 * directly. Only files under an "aegires" cache folder are accepted.
 */
internal object VideoPlayer {
    private var current: Dialog? = null

    fun allowed(path: String): Boolean = path.startsWith("/") && path.contains("/aegires/") && !path.contains("..")

    fun show(activity: Activity, path: String) {
        activity.runOnUiThread {
            try {
                if (!allowed(path) || !File(path).isFile) {
                    Toast.makeText(activity, "Video file not found", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                current?.dismiss()
                val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                val root = FrameLayout(activity)
                root.setBackgroundColor(Color.BLACK)

                val video = VideoView(activity)
                val controls = MediaController(activity)
                controls.setAnchorView(video)
                video.setMediaController(controls)
                video.setVideoPath(path)
                video.setOnPreparedListener { video.start() }
                video.setOnErrorListener { _, _, _ ->
                    Toast.makeText(activity, "Can't play this video format", Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                    true
                }
                root.addView(
                    video,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER),
                )

                val close = TextView(activity)
                close.text = "\u2715"
                close.setTextColor(Color.WHITE)
                close.textSize = 20f
                close.setPadding(36, 20, 36, 20)
                close.setBackgroundColor(0x99000000.toInt())
                close.setOnClickListener { dialog.dismiss() }
                val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
                lp.setMargins(0, 96, 24, 0)
                root.addView(close, lp)

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
