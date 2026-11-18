package aegires.e2ee

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/** What the controls need from whatever plays the media (a VideoView or a MediaPlayer). */
internal interface Transport {
    val playing: Boolean
    val position: Int
    val duration: Int
    fun play()
    fun pause()
    fun seekTo(ms: Int)

    /** False when this device or file cannot play at that speed. */
    fun setSpeed(speed: Float): Boolean
}

/** Small shared pieces of the player look: plain shapes and text, created once per player, nothing animated. */
internal object PlayerUi {
    val SPEEDS = floatArrayOf(1f, 1.25f, 1.5f, 2f, 3f, 4f, 5f)
    const val BLURPLE = 0xFF5865F2.toInt()
    const val DIM = 0xB3111214.toInt()
    const val SKIP_MS = 10_000

    fun dp(a: Activity, v: Float): Int = (v * a.resources.displayMetrics.density + 0.5f).toInt()

    fun speedLabel(s: Float): String = if (s == Math.floor(s.toDouble()).toFloat()) s.toInt().toString() + "x" else s.toString() + "x"

    fun fmt(ms: Int): String {
        val s = Math.max(ms, 0) / 1000
        val h = s / 3600
        val m = (s / 60) % 60
        val sec = (s % 60).toString().padStart(2, '0')
        return if (h > 0) h.toString() + ":" + m.toString().padStart(2, '0') + ":" + sec else m.toString() + ":" + sec
    }

    /** A round button holding a short label. */
    fun circle(a: Activity, label: String, sizeDp: Float, textSp: Float, fill: Int): TextView {
        val t = TextView(a)
        t.text = label
        t.setTextColor(Color.WHITE)
        t.textSize = textSp
        t.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        t.gravity = Gravity.CENTER
        t.includeFontPadding = false
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(fill)
        t.background = bg
        t.layoutParams = LinearLayout.LayoutParams(dp(a, sizeDp), dp(a, sizeDp))
        return t
    }

    /** A small rounded label button ("1.5x", a symbol). */
    fun pill(a: Activity, label: String, fill: Int = DIM, onTap: () -> Unit): TextView {
        val t = TextView(a)
        t.text = label
        t.setTextColor(Color.WHITE)
        t.textSize = 13f
        t.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(a, 10f), dp(a, 5f), dp(a, 10f), dp(a, 5f))
        val bg = GradientDrawable()
        bg.cornerRadius = dp(a, 14f).toFloat()
        bg.setColor(fill)
        t.background = bg
        t.setOnClickListener { onTap() }
        return t
    }

    fun scrim(top: Boolean): GradientDrawable {
        val o = if (top) GradientDrawable.Orientation.TOP_BOTTOM else GradientDrawable.Orientation.BOTTOM_TOP
        return GradientDrawable(o, intArrayOf(0xAA000000.toInt(), 0x00000000))
    }

    fun tint(sb: SeekBar) {
        sb.progressTintList = ColorStateList.valueOf(BLURPLE)
        sb.thumbTintList = ColorStateList.valueOf(BLURPLE)
        sb.progressBackgroundTintList = ColorStateList.valueOf(0x55FFFFFF)
    }

    fun skip(t: Transport, deltaMs: Int) {
        val dur = t.duration
        val to = t.position + deltaMs
        t.seekTo(if (dur > 0) to.coerceIn(0, Math.max(dur - 1, 0)) else Math.max(to, 0))
    }

    /** Sets the playback speed. A paused player must stay paused (setting the rate would start it). */
    fun applySpeed(mp: MediaPlayer?, speed: Float, wasPlaying: Boolean): Boolean {
        if (mp == null || Build.VERSION.SDK_INT < 23) return false
        return try {
            mp.playbackParams = mp.playbackParams.setSpeed(speed)
            if (!wasPlaying) mp.pause()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Moves to the next speed; returns the speed now in force (1x when the device refuses a speed, so it never gets stuck). */
    fun cycle(t: Transport, current: Float): Float {
        val next = nextSpeed(current)
        if (t.setSpeed(next)) return next
        return if (next != 1f && t.setSpeed(1f)) 1f else current
    }

    /** The next speed after [s] in [SPEEDS] (wraps back to 1x). */
    fun nextSpeed(s: Float): Float {
        for (i in SPEEDS.indices) if (SPEEDS[i] > s + 0.001f) return SPEEDS[i]
        return SPEEDS[0]
    }
}

/**
 * The control layer drawn over a video: top row (full screen, picture-in-picture, close), centre row
 * (back 10 s, play / pause, forward 10 s), bottom (seek bar, time, speed). It hides itself a few
 * seconds after it appears; [tick] refreshes it at most five times a second and only while visible.
 */
internal class PlayerControls(
    private val a: Activity,
    private val t: Transport,
    full: Boolean,
    onClose: () -> Unit,
    onFullscreen: (() -> Unit)?,
    onPip: (() -> Unit)?,
) {
    val layer = FrameLayout(a)
    var dragging = false
        private set
    var speed = 1f
        private set
    val visible: Boolean get() = layer.visibility == View.VISIBLE

    private val seek = SeekBar(a)
    private val time = TextView(a)
    private val play: TextView
    private val chip: TextView
    private var lastTick = 0L
    private var lastSymbol = ""
    private var lastTime = ""
    private val hide = Runnable { layer.visibility = View.GONE }

    private fun d(v: Float): Int = PlayerUi.dp(a, v)

    init {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val topScrim = View(a)
        topScrim.background = PlayerUi.scrim(true)
        layer.addView(topScrim, FrameLayout.LayoutParams(match, d(if (full) 96f else 56f), Gravity.TOP))
        val bottomScrim = View(a)
        bottomScrim.background = PlayerUi.scrim(false)
        layer.addView(bottomScrim, FrameLayout.LayoutParams(match, d(88f), Gravity.BOTTOM))

        // top row
        val top = LinearLayout(a)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(d(8f), d(if (full) 34f else 6f), d(8f), 0)
        fun gap(v: View) {
            val lp = LinearLayout.LayoutParams(wrap, wrap)
            lp.rightMargin = d(6f)
            top.addView(v, lp)
        }
        if (onFullscreen != null) gap(PlayerUi.pill(a, "⛶") { onFullscreen() })
        if (onPip != null) gap(PlayerUi.pill(a, "⧉") { onPip() })
        top.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(PlayerUi.pill(a, "✕") { onClose() })
        layer.addView(top, FrameLayout.LayoutParams(match, wrap, Gravity.TOP))

        // centre row
        val mid = LinearLayout(a)
        mid.orientation = LinearLayout.HORIZONTAL
        mid.gravity = Gravity.CENTER
        val back = PlayerUi.circle(a, "-10", 42f, 13f, PlayerUi.DIM)
        play = PlayerUi.circle(a, "⏸", 56f, 24f, PlayerUi.BLURPLE)
        val fwd = PlayerUi.circle(a, "+10", 42f, 13f, PlayerUi.DIM)
        back.setOnClickListener { PlayerUi.skip(t, -PlayerUi.SKIP_MS); poke() }
        fwd.setOnClickListener { PlayerUi.skip(t, PlayerUi.SKIP_MS); poke() }
        play.setOnClickListener {
            if (t.playing) t.pause() else t.play()
            poke()
        }
        mid.addView(back, LinearLayout.LayoutParams(d(42f), d(42f)))
        val playLp = LinearLayout.LayoutParams(d(56f), d(56f))
        playLp.leftMargin = d(18f)
        playLp.rightMargin = d(18f)
        mid.addView(play, playLp)
        mid.addView(fwd, LinearLayout.LayoutParams(d(42f), d(42f)))
        layer.addView(mid, FrameLayout.LayoutParams(wrap, wrap, Gravity.CENTER))

        // bottom: seek bar over (time ... speed)
        val bottom = LinearLayout(a)
        bottom.orientation = LinearLayout.VERTICAL
        bottom.setPadding(d(10f), 0, d(10f), d(6f))
        seek.setPadding(d(4f), 0, d(4f), 0)
        PlayerUi.tint(seek)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) t.seekTo(p)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {
                dragging = true
                layer.removeCallbacks(hide)
            }

            override fun onStopTrackingTouch(s: SeekBar?) {
                dragging = false
                poke()
            }
        })
        bottom.addView(seek, LinearLayout.LayoutParams(match, d(26f)))
        val row = LinearLayout(a)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        time.setTextColor(0xFFE3E5E8.toInt())
        time.textSize = 12f
        time.text = "0:00 / 0:00"
        row.addView(time, LinearLayout.LayoutParams(0, wrap, 1f))
        chip = PlayerUi.pill(a, "1x") { cycleSpeed() }
        row.addView(chip, LinearLayout.LayoutParams(wrap, wrap))
        bottom.addView(row, LinearLayout.LayoutParams(match, wrap))
        layer.addView(bottom, FrameLayout.LayoutParams(match, wrap, Gravity.BOTTOM))
    }

    private fun cycleSpeed() {
        speed = PlayerUi.cycle(t, speed)
        chip.text = PlayerUi.speedLabel(speed)
        poke()
    }

    /** Sets the speed a new player starts at (it is applied once the player is ready). */
    fun applyCurrentSpeed() {
        if (speed != 1f) t.setSpeed(speed)
    }

    /** Shows the layer and restarts its hide timer. */
    fun poke() {
        layer.visibility = View.VISIBLE
        layer.removeCallbacks(hide)
        if (!dragging) layer.postDelayed(hide, HIDE_MS)
        lastTick = 0L
        tick()
    }

    fun toggle() {
        if (visible) {
            layer.removeCallbacks(hide)
            layer.visibility = View.GONE
        } else {
            poke()
        }
    }

    fun tick() {
        val now = SystemClock.uptimeMillis()
        if (now - lastTick < 200L && lastTick != 0L) return
        lastTick = now
        if (!visible) return
        val dur = Math.max(t.duration, 1)
        if (seek.max != dur) seek.max = dur
        val pos = t.position
        if (!dragging) seek.progress = pos
        val text = PlayerUi.fmt(pos) + " / " + PlayerUi.fmt(dur)
        if (text != lastTime) {
            time.text = text
            lastTime = text
        }
        val sym = if (t.playing) "⏸" else "▶"
        if (sym != lastSymbol) {
            play.text = sym
            lastSymbol = sym
        }
    }

    companion object {
        private const val HIDE_MS = 2500L
    }
}
