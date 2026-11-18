package aegires.e2ee

import android.content.res.Resources
import android.graphics.drawable.GradientDrawable

/**
 * The optional "liquid glass" look (Settings > Behaviour). Plain shapes only, no library, no per-frame
 * work: a see-through fill that is lighter at the top, with a thin bright edge. The settings screen also
 * blurs what is behind it on Android 12 and newer. [on] is set from the saved setting when the plugin
 * starts, and from the settings switch; screens and players pick it up when they are created.
 */
internal object Glass {
    @Volatile var on = false

    /** Soft glass: the heavier mode (blurred wallpaper behind the settings cards, stronger blur behind the screen). Only counts while [on]. */
    @Volatile var soft = false

    private const val STROKE = 0x66FFFFFF
    private const val TOP = 0x40FFFFFF
    private const val BOTTOM = 0x66000000
    private const val ACCENT_TOP = 0xB07C88FF.toInt()
    private const val ACCENT_BOTTOM = 0xB03F49C8.toInt()

    private fun px(v: Float): Int = Math.max(1, (v * Resources.getSystem().displayMetrics.density + 0.5f).toInt())

    /** A glass shape; [accent] tints it blurple (the play button, the selected speed). */
    fun drawable(radius: Float, accent: Boolean = false, oval: Boolean = false): GradientDrawable {
        val g = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            if (accent) intArrayOf(ACCENT_TOP, ACCENT_BOTTOM) else intArrayOf(TOP, BOTTOM),
        )
        if (oval) g.shape = GradientDrawable.OVAL else g.cornerRadius = radius
        g.setStroke(px(1f), STROKE)
        return g
    }
}
