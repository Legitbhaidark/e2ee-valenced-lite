package aegires.e2ee

import android.app.Activity
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.SystemClock
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
 * button, the title, a seek bar, "position / length", back and forward 10 s and a speed button (1x to 5x). Found and followed the same way as the
 * video's picture ([InlineVideo.pictureAtLastTouch]); if the card cannot be found the full-screen
 * [VideoPlayer] is used, so a tap always plays.
 *
 * It pauses and goes away when its card is scrolled out of sight for a while, is re-used for another
 * message, or the audio ends. Starting a video or another audio closes it.
 */
internal object InlineAudio {
    private const val MIN_DP = 40 // an audio card is flat: about 100 dp high
    private const val CHIP_FILL = 0xFF2B2D31.toInt()

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

    private fun show(a: Activity, pic: View, filePath: String, fileTitle: String) {
        val decor = a.window.decorView as? ViewGroup ?: throw IllegalStateException("no decor")
        fun d(v: Float): Int = InlineVideo.dp(a, v)
        host = WeakReference(a)
        val rect0 = InlineVideo.winRect(pic)
        targetW = Math.round(rect0.width())
        targetH = Math.round(rect0.height())
        hiddenSince = 0L
        dragging = false
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        // The card: dark, rounded like Discord's cards, and it swallows taps so they do not reach the chat.
        val root = ClipFrame(a)
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
        row.setPadding(d(12f), d(6f), d(8f), d(6f))
        root.addView(row, FrameLayout.LayoutParams(match, match))

        val circle = PlayPauseView(a)
        circle.setPlaying(true)
        row.addView(circle, LinearLayout.LayoutParams(d(48f), d(48f)))

        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        val colLp = LinearLayout.LayoutParams(0, wrap, 1f)
        colLp.leftMargin = d(10f)
        row.addView(col, colLp)

        // title row: title, speed, close
        val head = LinearLayout(a)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val titleView = TextView(a)
        titleView.text = if (fileTitle.isBlank()) "Audio" else fileTitle
        titleView.setTextColor(Color.WHITE)
        titleView.textSize = 14f
        titleView.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        titleView.maxLines = 1
        titleView.ellipsize = TextUtils.TruncateAt.END
        head.addView(titleView, LinearLayout.LayoutParams(0, wrap, 1f))
        var speed = 1f
        val chip = PlayerUi.pill(a, "1x", CHIP_FILL) { }
        head.addView(chip, LinearLayout.LayoutParams(wrap, wrap).also { it.leftMargin = d(6f) })
        val closeBtn = PlayerUi.pill(a, "\u2715", CHIP_FILL) { close() }
        head.addView(closeBtn, LinearLayout.LayoutParams(wrap, wrap).also { it.leftMargin = d(6f) })
        col.addView(head, LinearLayout.LayoutParams(match, wrap))

        val seek = SeekBar(a)
        seek.setPadding(d(2f), 0, d(2f), 0)
        PlayerUi.tint(seek)
        col.addView(seek, LinearLayout.LayoutParams(match, d(24f)))

        // bottom row: back 10 s, time, forward 10 s
        val foot = LinearLayout(a)
        foot.orientation = LinearLayout.HORIZONTAL
        foot.gravity = Gravity.CENTER_VERTICAL
        val back = PlayerUi.pill(a, "-10", CHIP_FILL) { }
        val fwd = PlayerUi.pill(a, "+10", CHIP_FILL) { }
        val time = TextView(a)
        time.text = "0:00 / 0:00"
        time.setTextColor(0xFFB5BAC1.toInt())
        time.textSize = 12f
        time.gravity = Gravity.CENTER
        foot.addView(back, LinearLayout.LayoutParams(wrap, wrap))
        foot.addView(time, LinearLayout.LayoutParams(0, wrap, 1f))
        foot.addView(fwd, LinearLayout.LayoutParams(wrap, wrap))
        col.addView(foot, LinearLayout.LayoutParams(match, wrap))

        val mp = MediaPlayer()
        player = mp
        var ready = false
        val transport = object : Transport {
            override val playing: Boolean get() = try { mp.isPlaying } catch (_: Throwable) { false }
            override val position: Int get() = try { mp.currentPosition } catch (_: Throwable) { 0 }
            override val duration: Int get() = try { mp.duration } catch (_: Throwable) { 0 }
            override fun play() { if (ready) mp.start() }
            override fun pause() { if (ready) mp.pause() }
            override fun seekTo(ms: Int) { if (ready) mp.seekTo(ms) }
            override fun setSpeed(speed: Float): Boolean = ready && PlayerUi.applySpeed(mp, speed, playing)
        }
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
        circle.setOnClickListener { if (transport.playing) transport.pause() else transport.play() }
        back.setOnClickListener { PlayerUi.skip(transport, -PlayerUi.SKIP_MS) }
        fwd.setOnClickListener { PlayerUi.skip(transport, PlayerUi.SKIP_MS) }
        chip.setOnClickListener {
            speed = PlayerUi.cycle(transport, speed)
            chip.text = PlayerUi.speedLabel(speed)
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
        var lastTick = 0L
        var lastText = ""
        root.postOnAnimation(object : Runnable {
            override fun run() {
                if (box !== root || player !== mp) return
                if (!follow(root, pic, mp)) return
                // The labels are refreshed five times a second, not on every frame.
                val now = SystemClock.uptimeMillis()
                if (ready && now - lastTick >= 200L) {
                    lastTick = now
                    val pos = transport.position
                    if (!dragging) seek.progress = pos
                    val text = PlayerUi.fmt(pos) + " / " + PlayerUi.fmt(seek.max)
                    if (text != lastText) {
                        time.text = text
                        lastText = text
                    }
                    circle.setPlaying(transport.playing)
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
        val seen = ChatViewport.visible(root.context as Activity, pic, vis) // on screen and not behind the message box
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
