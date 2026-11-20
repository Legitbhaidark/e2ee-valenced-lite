package aegires.e2ee

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.widget.Switch

/**
 * The "Glass controls" look (Settings > Behaviour, needs Liquid glass): text boxes, buttons and switches
 * in the same glass as the cards, but built for reading. Every fill colour comes from [GlassMath], which
 * makes it dark and opaque enough that its text keeps a contrast of 7:1 (text boxes) or 4.5:1 (buttons)
 * even over a pure white wallpaper. The glass itself (a light edge, a hairline of shine, a soft light at
 * the top) is drawn around and over the fill and never lightens it by more than [GlassMath.HIGHLIGHT],
 * the amount the fills were checked with. Drawn shapes only, no per-frame work.
 */
internal object GlassControls {
    /** Placeholder text colour for glass text boxes. */
    val HINT: Int = GlassMath.HINT

    /**
     * A button. [tint] colours it (the blue accent, the red danger); [neutral] makes it plain dark glass
     * (the unchosen chips). Pressed gets darker, disabled turns plain so it reads as "not now".
     */
    fun button(radius: Float, tint: Int, neutral: Boolean): Drawable {
        val fill = if (neutral) GlassMath.neutralFill() else GlassMath.tintedFill(tint and 0xFFFFFF)
        val s = StateListDrawable()
        s.addState(intArrayOf(-android.R.attr.state_enabled), ControlDrawable(radius, GlassMath.neutralFill(), 0x50))
        s.addState(intArrayOf(android.R.attr.state_pressed), ControlDrawable(radius, GlassMath.pressed(fill), 0xE0))
        s.addState(intArrayOf(), ControlDrawable(radius, fill, 0xB0))
        return s
    }

    /** A text box. Its edge lights up while it has the keyboard (see [ControlDrawable.focused]). */
    fun field(radius: Float): ControlDrawable = ControlDrawable(radius, GlassMath.fieldFill(), 0x80)

    /** Gives a switch a glass track (blue when on, dark when off) and a light glass bead. */
    fun style(sw: Switch) {
        val d = sw.resources.displayMetrics.density
        val w = (52f * d + 0.5f).toInt()
        val h = (30f * d + 0.5f).toInt()
        val track = StateListDrawable()
        track.addState(intArrayOf(android.R.attr.state_checked), ControlDrawable(0f, GlassMath.tintedFill(GlassMath.ACCENT), 0xB0, pill = true, intrinsicW = w, intrinsicH = h))
        track.addState(intArrayOf(), ControlDrawable(0f, GlassMath.neutralFill(), 0xB0, pill = true, intrinsicW = w, intrinsicH = h))
        sw.trackDrawable = track
        sw.thumbDrawable = BeadDrawable(h, 3f * d)
        sw.showText = false
        sw.switchMinWidth = w
    }
}

/** A glass button, text box or switch track. A [radius] of 0 with [pill] makes the ends fully round. */
internal class ControlDrawable(
    private val radius: Float,
    private val fill: Int,
    private val rimPeak: Int,
    private val pill: Boolean = false,
    private val intrinsicW: Int = -1,
    private val intrinsicH: Int = -1,
) : Drawable() {
    /** The edge lights up in the accent colour (a text box that has the keyboard). */
    var focused: Boolean = false
        set(v) {
            if (field != v) {
                field = v
                invalidateSelf()
            }
        }

    private val density = Resources.getSystem().displayMetrics.density
    private val rimWidth = Math.max(1f, 1.1f * density)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hairline = Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.STROKE; it.strokeWidth = Math.max(1f, density * 0.8f) }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.STROKE; it.strokeWidth = rimWidth }
    private val focusRim = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.style = Paint.Style.STROKE
        it.strokeWidth = Math.max(1.5f, 1.8f * density)
        it.color = 0xFF9AA8FF.toInt()
    }
    private val outer = RectF()
    private val edge = RectF()
    private val line = RectF()
    private var r = radius

    override fun getIntrinsicWidth(): Int = intrinsicW

    override fun getIntrinsicHeight(): Int = intrinsicH

    override fun onBoundsChange(b: Rect) {
        outer.set(b)
        edge.set(outer)
        edge.inset(rimWidth / 2f, rimWidth / 2f)
        line.set(outer)
        line.inset(2f * density, 2f * density)
        if (b.width() <= 0 || b.height() <= 0) return
        val h = b.height().toFloat()
        r = if (pill) Math.min(b.width(), b.height()) / 2f else radius
        fillPaint.shader = LinearGradient(0f, outer.top, 0f, outer.bottom, fill, GlassMath.mix(fill, 0xFF000000.toInt(), 0.12f), Shader.TileMode.CLAMP)
        // A soft light at the top. Its strongest alpha (0x1A) stays under GlassMath.HIGHLIGHT.
        shine.shader = LinearGradient(0f, outer.top, 0f, outer.top + h * 0.55f, 0x1AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        // Light catches the top-left and the bottom-right edge; the sides in between stay faint.
        rim.shader = LinearGradient(
            outer.left, outer.top, outer.right, outer.bottom,
            intArrayOf((rimPeak shl 24) or 0xFFFFFF, 0x30FFFFFF, 0x12FFFFFF, ((rimPeak * 11 / 20) shl 24) or 0xFFFFFF),
            floatArrayOf(0f, 0.38f, 0.64f, 1f), Shader.TileMode.CLAMP,
        )
        hairline.shader = LinearGradient(0f, line.top, 0f, line.top + h * 0.4f, 0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
    }

    override fun draw(c: Canvas) {
        if (bounds.isEmpty) return
        c.drawRoundRect(outer, r, r, fillPaint)
        c.drawRoundRect(outer, r, r, shine)
        val lr = Math.max(0f, r - 2f * density)
        c.drawRoundRect(line, lr, lr, hairline)
        val er = Math.max(0f, r - rimWidth / 2f)
        c.drawRoundRect(edge, er, er, if (focused) focusRim else rim)
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(cf: ColorFilter?) {}

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** The round bead of a glass switch: a light glass ball with a soft shadow and a gloss at the top. */
private class BeadDrawable(private val size: Int, private val margin: Float) : Drawable() {
    private val density = Resources.getSystem().displayMetrics.density
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = 0x38000000 }
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gloss = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = 0x66FFFFFF }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.STROKE; it.strokeWidth = Math.max(1f, density * 0.8f); it.color = 0x40000000 }
    private val ball = RectF()
    private val shade = RectF()
    private val shineRect = RectF()

    override fun getIntrinsicWidth(): Int = size

    override fun getIntrinsicHeight(): Int = size

    override fun onBoundsChange(b: Rect) {
        ball.set(b)
        ball.inset(margin, margin)
        shade.set(ball)
        shade.offset(0f, density)
        body.shader = LinearGradient(0f, ball.top, 0f, ball.bottom, GlassMath.THUMB_TOP, GlassMath.THUMB_BOTTOM, Shader.TileMode.CLAMP)
        val w = ball.width()
        shineRect.set(ball.left + w * 0.2f, ball.top + ball.height() * 0.08f, ball.right - w * 0.2f, ball.top + ball.height() * 0.45f)
    }

    override fun draw(c: Canvas) {
        if (bounds.isEmpty) return
        c.drawOval(shade, shadow)
        c.drawOval(ball, body)
        c.drawOval(shineRect, gloss)
        c.drawOval(ball, edge)
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(cf: ColorFilter?) {}

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
