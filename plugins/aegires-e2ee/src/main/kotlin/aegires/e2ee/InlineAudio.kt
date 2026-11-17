package aegires.e2ee

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.io.File
import java.lang.ref.WeakReference

/**
 * Plays a decrypted audio file IN the chat. The chat shows the audio as a card picture (made by
 * [MediaThumb]); a tap on it lands here, and this draws a player over that card: a play / pause
 * button, the title, a seek bar and "position / length". Found and followed the same way as the
 * video's picture ([InlineVideo.pictureAtLastTouch]); if the card cannot be found the full-screen
 * [VideoPlayer] is used, so a tap always plays.
 *
 * It pauses and goes away when its card is scrolled out of sight for a while, is re-used for another
 * message, or the audio ends. Starting a video or another audio closes it.
 */
internal object InlineAudio {
    private const val MIN_DP = 40 // an audio card is flat: about 100 dp high
    private val BLURPLE = 0xFF5865F2.toInt()

    private var box: FrameLayout? = null
    private var player: MediaPlayer? = null
    private var host: WeakReference<Activity>? = null
    private var targetW = 0
    private var targetH = 0
    private var hiddenSince = 0L
    private var dragging = false

    /** True for the player's own view, so the picture search never picks it. */
    fun owns(v: View): Boolean = v === box

    fun start(activity: Activity, filePath: String, fileTitle: String, vw: Int, vh: Int) {
        InlineVideo.runUi(activity) {
            try {
                InlineVideo.close()
                close()
                if (!VideoPlayer.allowed(filePath) || !File(filePath).isFile) {
                    InlineVideo.note("audio: full screen: file not found")
                    VideoPlayer.show(activity, filePath, fileTitle)
                    return@runUi
                }
                val card = InlineVideo.pictureAtLastTouch(activity, vw, vh, MIN_DP)
                if (card == null) {
                    VideoPlayer.show(activity, filePath, fileTitle)
                    return@runUi
                }
                show(activity, card, filePath, fileTitle)
                InlineVideo.note("audio inline on " + card.javaClass.name + " " + targetW + "x" + targetH)
            } catch (t: Throwable) {
                close()
                InlineVideo.note("audio: full screen: error " + t.javaClass.simpleName + " " + (t.message ?: "").take(60))
                VideoPlayer.show(activity, filePath, fileTitle)
            }
        }
    }

    fun close() {
        val b = box
        val p = player
        box = null
        player = null
        host = null
        try {
            p?.stop()
        } catch (_: Throwable) {
        }
        try {
            p?.release()
        } catch (_: Throwable) {
        }
        try {
            (b?.parent as? ViewGroup)?.removeView(b)
        } catch (_: Throwable) {
        }
    }

    private fun fmt(ms: Int): String {
        val s = Math.max(ms, 0) / 1000
        return (s / 60).toString() + ":" + (s % 60).toString().padStart(2, '0')
    }

    private fun show(a: Activity, pic: View, filePath: String, fileTitle: String) {
        val decor = a.window.decorView as? ViewGroup ?: throw IllegalStateException("no decor")
        fun d(v: Float): Int = InlineVideo.dp(a, v)
        host = WeakReference(a)
        val rect0 = InlineVideo.winRect(pic)
        targetW = Math.round(rect0.width())
        targetH = Math.round(rect0.height())
        hiddenSince = 0L
        dragging = false

        // The card: dark, rounded like Discord's cards, and it swallows taps so they do not reach the chat.
        val root = FrameLayout(a)
        val bg = GradientDrawable()
        bg.setColor(0xFF1E1F22.toInt())
        bg.cornerRadius = d(18f).toFloat()
        root.background = bg
        root.clipToOutline = true
        root.outlineProvider = ViewOutlineProvider.BACKGROUND
        root.isClickable = true

        val row = LinearLayout(a)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(d(14f), d(6f), d(10f), d(6f))
        root.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val circle = TextView(a)
        circle.gravity = Gravity.CENTER
        circle.setTextColor(Color.WHITE)
        circle.textSize = 22f
        circle.text = "\u23F8"
        val circleBg = GradientDrawable()
        circleBg.shape = GradientDrawable.OVAL
        circleBg.setColor(BLURPLE)
        circle.background = circleBg
        row.addView(circle, LinearLayout.LayoutParams(d(52f), d(52f)))

        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        val colLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        colLp.leftMargin = d(12f)
        row.addView(col, colLp)

        val titleView = TextView(a)
        titleView.text = if (fileTitle.isBlank()) "Audio" else fileTitle
        titleView.setTextColor(Color.WHITE)
        titleView.textSize = 15f
        titleView.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        titleView.maxLines = 1
        titleView.ellipsize = TextUtils.TruncateAt.END
        col.addView(titleView)

        val seek = SeekBar(a)
        seek.setPadding(0, 0, 0, 0)
        seek.progressTintList = ColorStateList.valueOf(BLURPLE)
        seek.thumbTintList = ColorStateList.valueOf(BLURPLE)
        col.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, d(26f)))

        val time = TextView(a)
        time.text = "0:00 / 0:00"
        time.setTextColor(0xFFB5BAC1.toInt())
        time.textSize = 12f
        col.addView(time)

        val closeBtn = TextView(a)
        closeBtn.text = "\u2715"
        closeBtn.setTextColor(0xFFB5BAC1.toInt())
        closeBtn.textSize = 16f
        closeBtn.gravity = Gravity.CENTER
        closeBtn.setPadding(d(10f), d(6f), d(6f), d(6f))
        closeBtn.setOnClickListener { close() }
        row.addView(closeBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val mp = MediaPlayer()
        player = mp
        var ready = false
        mp.setDataSource(filePath)
        mp.setOnPreparedListener {
            ready = true
            seek.max = it.duration.coerceAtLeast(1)
            it.start()
        }
        mp.setOnCompletionListener { close() } // back to the card
        mp.setOnErrorListener { _, _, _ ->
            val act = host?.get()
            close()
            if (act != null) VideoPlayer.show(act, filePath, fileTitle)
            true
        }
        circle.setOnClickListener {
            if (ready) {
                if (mp.isPlaying) mp.pause() else mp.start()
            }
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser && ready) mp.seekTo(p)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {
                dragging = true
            }

            override fun onStopTrackingTouch(s: SeekBar?) {
                dragging = false
            }
        })
        mp.prepareAsync()

        decor.addView(root, FrameLayout.LayoutParams(targetW, targetH, Gravity.TOP or Gravity.START))
        box = root
        follow(root, pic, mp)
        root.postOnAnimation(object : Runnable {
            override fun run() {
                if (box !== root || player !== mp) return
                if (!follow(root, pic, mp)) return
                if (ready) {
                    val pos = try { mp.currentPosition } catch (_: Throwable) { 0 }
                    if (!dragging) seek.progress = pos
                    val text = fmt(pos) + " / " + fmt(seek.max)
                    if (time.text.toString() != text) time.text = text
                    val playing = try { mp.isPlaying } catch (_: Throwable) { false }
                    val symbol = if (playing) "\u23F8" else "\u25B6"
                    if (circle.text.toString() != symbol) circle.text = symbol
                }
                root.postOnAnimation(this)
            }
        })
    }

    /**
     * Keeps the player on its card. Returns false (after closing) when the card is gone, was re-used
     * for something else, or has been scrolled out of sight for a while.
     */
    private fun follow(root: FrameLayout, pic: View, mp: MediaPlayer): Boolean {
        if (!pic.isAttachedToWindow) {
            close()
            return false
        }
        val full = InlineVideo.winRect(pic)
        if (Math.abs(Math.round(full.width()) - targetW) > 2 || Math.abs(Math.round(full.height()) - targetH) > 2) {
            close() // the chat re-used this view for something else
            return false
        }
        val vis = Rect()
        val seen = pic.isShown && pic.getGlobalVisibleRect(vis) && vis.height() * 10 >= full.height() * 3
        if (!seen) {
            root.visibility = View.INVISIBLE
            try {
                if (mp.isPlaying) mp.pause()
            } catch (_: Throwable) {
            }
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
        root.clipBounds = Rect(
            Math.round(vis.left - full.left), Math.round(vis.top - full.top),
            Math.round(vis.right - full.left), Math.round(vis.bottom - full.top),
        )
        return true
    }
}
