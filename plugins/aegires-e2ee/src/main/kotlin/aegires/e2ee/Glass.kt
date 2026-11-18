package aegires.e2ee

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * The optional "liquid glass" look (Settings > Behaviour). Drawn shapes only, no library and no
 * per-frame work: a see-through fill that is lighter at the top, an edge that catches light on two
 * opposite corners (the way a thick glass pane does), a thin bright line just inside the top edge and a
 * soft glow in the top-left corner. The settings screen also blurs what is behind it on Android 12 and
 * newer, and with a wallpaper and soft glass the cards bend it at their edges on Android 13 and newer
 * (see Wallpaper.kt). [on] is set from the saved setting when the plugin starts, and from the settings
 * switch; screens and players pick it up when they are created.
 */
internal object Glass {
    @Volatile var on = false

    /** Soft glass: the heavier mode (blurred wallpaper behind the settings cards, stronger blur behind the screen). Only counts while [on]. */
    @Volatile var soft = false

    private fun px(v: Float): Float = Math.max(1f, v * Resources.getSystem().displayMetrics.density)

    /**
     * A glass shape. [accent] tints it blurple (the play button, the selected speed); [tint] tints it with
     * any other colour (the green "E2EE is on" button) and wins over [accent].
     */
    fun drawable(radius: Float, accent: Boolean = false, oval: Boolean = false, tint: Int = 0): Drawable =
        GlassDrawable(radius, accent, oval, tint, px(1.2f), px(2f))
}

private class GlassDrawable(
    private val radius: Float,
    accent: Boolean,
    private val oval: Boolean,
    tint: Int,
    private val rimWidth: Float,
    private val sheenInset: Float,
) : Drawable() {
    private val top: Int
    private val bottom: Int
    private val tinted = accent || tint != 0

    init {
        val base = if (tint != 0) tint or 0xFF000000.toInt() else 0xFF5865F2.toInt()
        if (tinted) {
            top = withAlpha(mix(base, Color.WHITE, 0.30f), 0xB8)
            bottom = withAlpha(mix(base, Color.BLACK, 0.18f), 0xB8)
        } else {
            top = 0x40FFFFFF
            bottom = 0x66000000
        }
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.STROKE; it.strokeWidth = rimWidth }
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.STROKE; it.strokeWidth = Math.max(1f, rimWidth * 0.8f) }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outer = RectF()
    private val edge = RectF()
    private val inner = RectF()
    private val clip = Path()

    override fun onBoundsChange(b: Rect) {
        outer.set(b)
        edge.set(outer)
        edge.inset(rimWidth / 2f, rimWidth / 2f)
        inner.set(outer)
        inner.inset(sheenInset, sheenInset)
        if (b.width() <= 0 || b.height() <= 0) return
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        fill.shader = LinearGradient(0f, outer.top, 0f, outer.bottom, top, bottom, Shader.TileMode.CLAMP)
        // Light catches the top-left and the bottom-right edge; the sides in between stay faint.
        rim.shader = LinearGradient(
            outer.left, outer.top, outer.right, outer.bottom,
            intArrayOf(0xD0FFFFFF.toInt(), 0x38FFFFFF, 0x14FFFFFF, 0x78FFFFFF),
            floatArrayOf(0f, 0.38f, 0.64f, 1f), Shader.TileMode.CLAMP,
        )
        sheen.shader = LinearGradient(
            0f, inner.top, 0f, inner.top + h * 0.45f,
            0x5CFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP,
        )
        glow.shader = RadialGradient(
            outer.left + w * 0.2f, outer.top + h * 0.08f, Math.max(w, h) * 0.6f,
            intArrayOf(if (tinted) 0x40FFFFFF else 0x2EFFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP,
        )
        clip.reset()
        if (oval) clip.addOval(outer, Path.Direction.CW) else clip.addRoundRect(outer, radius, radius, Path.Direction.CW)
    }

    override fun draw(c: Canvas) {
        if (bounds.isEmpty) return
        shape(c, outer, radius, fill)
        c.save()
        c.clipPath(clip)
        c.drawRect(outer, glow)
        c.restore()
        shape(c, inner, Math.max(0f, radius - sheenInset), sheen)
        shape(c, edge, Math.max(0f, radius - rimWidth / 2f), rim)
    }

    private fun shape(c: Canvas, r: RectF, rad: Float, p: Paint) {
        if (oval) c.drawOval(r, p) else c.drawRoundRect(r, rad, rad, p)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun withAlpha(c: Int, a: Int): Int = (a shl 24) or (c and 0xFFFFFF)

    private fun mix(a: Int, b: Int, t: Float): Int {
        val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt()
        val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt()
        val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
        return Color.rgb(r, g, bl)
    }
}
