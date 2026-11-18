package aegires.e2ee

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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
    /** Slow, normal, fast. The video speed panel shows them in these three groups. */
    val SLOW = floatArrayOf(0.25f, 0.5f, 0.75f)
    val NORMAL = floatArrayOf(1f, 1.25f, 1.5f)
    val FAST = floatArrayOf(2f, 3f, 4f, 5f)
    val SPEEDS = SLOW + NORMAL + FAST
    const val BLURPLE = 0xFF5865F2.toInt()
    const val DIM = 0xB3111214.toInt()
    const val SKIP_MS = 10_000

    fun dp(a: Activity, v: Float): Int = (v * a.resources.displayMetrics.density + 0.5f).toInt()

    fun speedLabel(s: Float): String {
        val whole = Math.floor(s.toDouble()).toFloat()
        return if (s == whole) s.toInt().toString() + "x" else s.toString() + "x"
    }

    fun fmt(ms: Int): String {
        val s = Math.max(ms, 0) / 1000
        val h = s / 3600
        val m = (s / 60) % 60
        val sec = (s % 60).toString().padStart(2, '0')
        return if (h > 0) h.toString() + ":" + m.toString().padStart(2, '0') + ":" + sec else m.toString() + ":" + sec
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
        t.background = rounded(dp(a, 14f).toFloat(), fill)
        t.setOnClickListener { onTap() }
        return t
    }

    fun rounded(radius: Float, fill: Int): android.graphics.drawable.Drawable {
        if (Glass.on) return Glass.drawable(radius, fill == BLURPLE)
        val bg = GradientDrawable()
        bg.cornerRadius = radius
        bg.setColor(fill)
        return bg
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

    /** The next speed after [s] in [SPEEDS] (wraps back to the slowest). */
    fun nextSpeed(s: Float): Float {
        for (i in SPEEDS.indices) if (SPEEDS[i] > s + 0.001f) return SPEEDS[i]
        return SPEEDS[0]
    }
}

/**
 * A round play / pause button. The symbol is drawn, not typed: the pause character is an emoji on
 * many phones (orange, with a yellow border) and would not match the rest of the controls.
 */
internal class PlayPauseView(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val bar = RectF()
    private var showPause = false

    init {
        if (Glass.on) {
            background = Glass.drawable(0f, true, true)
        } else {
            val bg = GradientDrawable()
            bg.shape = GradientDrawable.OVAL
            bg.setColor(PlayerUi.BLURPLE)
            background = bg
        }
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
    }

    /** True shows the pause symbol (it is playing). */
    fun setPlaying(playing: Boolean) {
        if (playing == showPause) return
        showPause = playing
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val s = Math.min(w, h) * 0.36f // half the symbol's height
        val cx = w / 2f
        val cy = h / 2f
        if (showPause) {
            val bw = s * 0.62f
            val gap = s * 0.42f
            bar.set(cx - gap / 2f - bw, cy - s, cx - gap / 2f, cy + s)
            c.drawRoundRect(bar, bw / 4f, bw / 4f, paint)
            bar.set(cx + gap / 2f, cy - s, cx + gap / 2f + bw, cy + s)
            c.drawRoundRect(bar, bw / 4f, bw / 4f, paint)
        } else {
            val shift = s * 0.12f // a triangle looks centred when its centre of mass is
            path.reset()
            path.moveTo(cx - s * 0.7f + shift, cy - s)
            path.lineTo(cx + s * 0.95f + shift, cy)
            path.lineTo(cx - s * 0.7f + shift, cy + s)
            path.close()
            c.drawPath(path, paint)
        }
    }
}

/**
 * The control layer drawn over a video, and the touch rules of the video:
 *  - one tap shows or hides the controls;
 *  - a double tap on the left / right half jumps back / forward 10 s, and while the jump label is on
 *    screen every further tap jumps again (20 s, 30 s ...), like YouTube;
 *  - top row: full screen, picture-in-picture (hold it for "video only" or "whole app"), close;
 *    centre: play / pause; bottom: seek bar, time and the speed button (opens a speed panel).
 * The controls hide themselves after a few seconds. [tick] refreshes them at most five times a second,
 * and only while they are visible.
 */
internal class PlayerControls(
    private val a: Activity,
    private val t: Transport,
    private val full: Boolean,
    onClose: () -> Unit,
    onFullscreen: (() -> Unit)?,
    private val onPip: ((Boolean) -> Unit)?,
) {
    /** Add this to the player. It is always on screen; the controls inside it come and go. */
    val layer = FrameLayout(a)
    var dragging = false
        private set
    var speed = 1f
        private set
    val visible: Boolean get() = bar.visibility == View.VISIBLE

    private val bar = FrameLayout(a)
    private val seek = SeekBar(a)
    private val time = TextView(a)
    private val play = PlayPauseView(a)
    private val chip: TextView
    private val speedPanel = LinearLayout(a)
    private val speedChips = ArrayList<Pair<Float, TextView>>()
    private val pipPanel = LinearLayout(a)
    private val leftLabel = TextView(a)
    private val rightLabel = TextView(a)
    private var lastTick = 0L
    private var lastTime = ""
    private var lastTapAt = 0L
    private var pendingToggle: Runnable? = null
    private var seekUntil = 0L
    private var seekSide = 0
    private var seekTotal = 0
    private val hide = Runnable { hideBar() }
    private val hideLabels = Runnable {
        leftLabel.visibility = View.GONE
        rightLabel.visibility = View.GONE
    }
    private val chipOn = PlayerUi.rounded(PlayerUi.dp(a, 12f).toFloat(), PlayerUi.BLURPLE)
    private val chipOff = if (Glass.on) null else PlayerUi.rounded(PlayerUi.dp(a, 12f).toFloat(), 0xCC2B2D31.toInt())

    private fun d(v: Float): Int = PlayerUi.dp(a, v)

    init {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        layer.addView(bar, FrameLayout.LayoutParams(match, match))

        val topScrim = View(a)
        topScrim.background = PlayerUi.scrim(true)
        bar.addView(topScrim, FrameLayout.LayoutParams(match, d(if (full) 96f else 56f), Gravity.TOP))
        val bottomScrim = View(a)
        bottomScrim.background = PlayerUi.scrim(false)
        bar.addView(bottomScrim, FrameLayout.LayoutParams(match, d(if (full) 120f else 88f), Gravity.BOTTOM))

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
        if (onPip != null) {
            val pip = PlayerUi.pill(a, "⧉") {
                pipPanel.visibility = View.GONE
                onPip(false) // a tap: just the video
            }
            pip.setOnLongClickListener { // a hold: choose
                speedPanel.visibility = View.GONE
                pipPanel.visibility = if (pipPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                keepOpen()
                true
            }
            gap(pip)
        }
        top.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(PlayerUi.pill(a, "✕") { onClose() })
        bar.addView(top, FrameLayout.LayoutParams(match, wrap, Gravity.TOP))

        // picture-in-picture choice (hold the PiP button)
        pipPanel.orientation = LinearLayout.VERTICAL
        pipPanel.visibility = View.GONE
        pipPanel.setPadding(d(6f), d(6f), d(6f), d(6f))
        pipPanel.background = PlayerUi.rounded(d(14f).toFloat(), 0xE61E1F22.toInt())
        if (onPip != null) {
            pipPanel.addView(PlayerUi.pill(a, "Video only", 0xFF2B2D31.toInt()) { pipPanel.visibility = View.GONE; onPip(false) })
            val whole = PlayerUi.pill(a, "Whole app", 0xFF2B2D31.toInt()) { pipPanel.visibility = View.GONE; onPip(true) }
            pipPanel.addView(whole, LinearLayout.LayoutParams(wrap, wrap).also { it.topMargin = d(6f) })
        }
        val pipLp = FrameLayout.LayoutParams(wrap, wrap, Gravity.TOP or Gravity.START)
        pipLp.leftMargin = d(8f)
        pipLp.topMargin = d(if (full) 76f else 48f)
        bar.addView(pipPanel, pipLp)

        // centre: play / pause
        play.setOnClickListener {
            if (t.playing) t.pause() else t.play()
            keepOpen()
        }
        bar.addView(play, FrameLayout.LayoutParams(d(58f), d(58f), Gravity.CENTER))

        // bottom: seek bar over (time ... speed)
        val bottom = LinearLayout(a)
        bottom.orientation = LinearLayout.VERTICAL
        bottom.setPadding(d(10f), 0, d(10f), d(if (full) 30f else 6f))
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
                keepOpen()
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
        chip = PlayerUi.pill(a, "1x") {
            pipPanel.visibility = View.GONE
            speedPanel.visibility = if (speedPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            keepOpen()
        }
        row.addView(chip, LinearLayout.LayoutParams(wrap, wrap))
        bottom.addView(row, LinearLayout.LayoutParams(match, wrap))
        bar.addView(bottom, FrameLayout.LayoutParams(match, wrap, Gravity.BOTTOM))

        // speed panel: slow / normal / fast, three rows above the speed button
        speedPanel.orientation = LinearLayout.VERTICAL
        speedPanel.visibility = View.GONE
        speedPanel.setPadding(d(6f), d(6f), d(6f), d(6f))
        speedPanel.background = PlayerUi.rounded(d(14f).toFloat(), 0xE61E1F22.toInt())
        for (group in arrayOf(PlayerUi.SLOW, PlayerUi.NORMAL, PlayerUi.FAST)) {
            val line = LinearLayout(a)
            line.orientation = LinearLayout.HORIZONTAL
            for (s in group) {
                val c = TextView(a)
                c.text = PlayerUi.speedLabel(s)
                c.setTextColor(Color.WHITE)
                c.textSize = 12f
                c.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                c.gravity = Gravity.CENTER
                c.setPadding(d(8f), d(5f), d(8f), d(5f))
                c.setOnClickListener { chooseSpeed(s) }
                val lp = LinearLayout.LayoutParams(wrap, wrap)
                lp.setMargins(d(2f), d(2f), d(2f), d(2f))
                line.addView(c, lp)
                speedChips.add(Pair(s, c))
            }
            speedPanel.addView(line, LinearLayout.LayoutParams(wrap, wrap))
        }
        refreshChips()
        val spLp = FrameLayout.LayoutParams(wrap, wrap, Gravity.BOTTOM or Gravity.END)
        spLp.rightMargin = d(8f)
        spLp.bottomMargin = d(if (full) 96f else 72f)
        bar.addView(speedPanel, spLp)

        // jump labels ("-10 s" / "+20 s"), outside the bar so they show while the controls are hidden
        for (lab in arrayOf(leftLabel, rightLabel)) {
            lab.setTextColor(Color.WHITE)
            lab.textSize = 15f
            lab.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            lab.gravity = Gravity.CENTER
            lab.setPadding(d(16f), d(10f), d(16f), d(10f))
            lab.background = PlayerUi.rounded(d(24f).toFloat(), 0xB3111214.toInt())
            lab.visibility = View.GONE
        }
        val ll = FrameLayout.LayoutParams(wrap, wrap, Gravity.CENTER_VERTICAL or Gravity.START)
        ll.leftMargin = d(16f)
        layer.addView(leftLabel, ll)
        val rl = FrameLayout.LayoutParams(wrap, wrap, Gravity.CENTER_VERTICAL or Gravity.END)
        rl.rightMargin = d(16f)
        layer.addView(rightLabel, rl)

        if (full) {
            // Full screen draws behind the system bars: keep the bottom row clear of the gesture bar.
            layer.setOnApplyWindowInsetsListener { _, ins ->
                @Suppress("DEPRECATION")
                bottom.setPadding(d(10f), 0, d(10f), Math.max(d(30f), ins.systemWindowInsetBottom + d(8f)))
                ins
            }
        }
    }

    private fun chooseSpeed(s: Float) {
        if (t.setSpeed(s)) speed = s
        chip.text = PlayerUi.speedLabel(speed)
        refreshChips()
        speedPanel.visibility = View.GONE
        keepOpen()
    }

    private fun refreshChips() {
        for ((s, c) in speedChips) {
            val want = if (Math.abs(s - speed) < 0.001f) chipOn else chipOff
            if (c.background !== want) c.background = want // null = no fill (glass: the panel behind shows)
        }
    }

    /** Sets the speed a new player starts at (it is applied once the player is ready). */
    fun applyCurrentSpeed() {
        if (speed != 1f) t.setSpeed(speed)
    }

    // ------------------------------------------------------------------ showing and hiding

    private fun keepOpen() {
        bar.visibility = View.VISIBLE
        layer.removeCallbacks(hide)
        if (!dragging) layer.postDelayed(hide, HIDE_MS)
        lastTick = 0L
        tick()
    }

    private fun hideBar() {
        layer.removeCallbacks(hide)
        speedPanel.visibility = View.GONE
        pipPanel.visibility = View.GONE
        bar.visibility = View.GONE
    }

    /** Shows the controls and restarts their hide timer. */
    fun poke() = keepOpen()

    fun toggle() {
        if (visible) hideBar() else keepOpen()
    }

    fun tick() {
        val now = SystemClock.uptimeMillis()
        if (lastTick != 0L && now - lastTick < 200L) return
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
        play.setPlaying(t.playing)
    }

    // ------------------------------------------------------------------ touches

    /** Makes [target] (the player's frame) understand taps: show / hide controls, double tap to jump. */
    fun attachTo(target: View) {
        val slop = ViewConfiguration.get(a).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        target.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x
                    downY = ev.y
                }
                MotionEvent.ACTION_UP -> {
                    if (Math.abs(ev.x - downX) <= slop && Math.abs(ev.y - downY) <= slop) tapped(ev.x, v.width)
                }
            }
            true
        }
    }

    private fun tapped(x: Float, width: Int) {
        val now = SystemClock.uptimeMillis()
        if (now < seekUntil) { // the jump label is still up: every tap jumps again
            jump(x, width, now)
            return
        }
        val pending = pendingToggle
        if (pending != null && now - lastTapAt <= DOUBLE_TAP_MS) { // the second tap of a double tap
            layer.removeCallbacks(pending)
            pendingToggle = null
            jump(x, width, now)
            return
        }
        lastTapAt = now
        val toggle = Runnable {
            pendingToggle = null
            toggle()
        }
        pendingToggle = toggle
        layer.postDelayed(toggle, DOUBLE_TAP_MS)
    }

    private fun jump(x: Float, width: Int, now: Long) {
        val side = if (x < width / 2f) -1 else 1
        if (side != seekSide || now >= seekUntil) seekTotal = 0
        seekSide = side
        seekTotal += PlayerUi.SKIP_MS / 1000
        seekUntil = now + LABEL_MS
        PlayerUi.skip(t, side * PlayerUi.SKIP_MS)
        val label = if (side < 0) leftLabel else rightLabel
        val other = if (side < 0) rightLabel else leftLabel
        other.visibility = View.GONE
        label.text = (if (side < 0) "-" else "+") + seekTotal + " s"
        label.visibility = View.VISIBLE
        layer.removeCallbacks(hideLabels)
        layer.postDelayed(hideLabels, LABEL_MS)
        lastTick = 0L
        tick()
    }

    companion object {
        private const val HIDE_MS = 2500L
        private const val DOUBLE_TAP_MS = 300L
        private const val LABEL_MS = 800L
    }
}
