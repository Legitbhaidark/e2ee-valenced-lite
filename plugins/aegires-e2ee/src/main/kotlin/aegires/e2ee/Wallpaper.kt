package aegires.e2ee

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import java.io.File
import java.io.FileOutputStream

/**
 * The settings wallpaper: one picture the user picks, kept (shrunk) in the app's own files. With soft
 * glass on, the settings cards show a blurred copy of the wallpaper behind them ([frost]). The blur is
 * made once, on a small copy of the picture, and is then only drawn: no live blur, no GPU effect.
 */
internal object Wallpaper {
    private const val REQUEST = 4721
    private const val MAX_SIDE = 1600
    internal var callback: ((Boolean) -> Unit)? = null
    private var full: Bitmap? = null
    private var blurred: Bitmap? = null
    private var blurKey = ""

    private fun file(a: Activity) = File(a.filesDir, "aegires-wallpaper.jpg")

    fun has(a: Activity): Boolean = file(a).isFile

    fun remove(a: Activity) {
        try {
            file(a).delete()
        } catch (_: Throwable) {
        }
        clearCaches()
    }

    private fun clearCaches() {
        full = null
        blurred = null
        blurKey = ""
    }

    /** Opens the system picture chooser; [done] gets true when a picture was picked and saved. */
    @Suppress("DEPRECATION")
    fun pick(a: Activity, done: (Boolean) -> Unit) {
        callback = done
        try {
            a.fragmentManager.beginTransaction().add(WallpaperPick(), "aegires-wallpaper").commitAllowingStateLoss()
        } catch (_: Throwable) {
            callback = null
            done(false)
        }
    }

    internal fun save(a: Activity, uri: Uri): Boolean {
        try {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            a.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val big = Math.max(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (big / (sample * 2) >= MAX_SIDE) sample *= 2
            val opts = BitmapFactory.Options()
            opts.inSampleSize = sample
            val bmp = a.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return false
            FileOutputStream(file(a)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            clearCaches()
            return true
        } catch (_: Throwable) {
            return false
        }
    }

    private fun full(a: Activity): Bitmap? {
        full?.let { return it }
        val b = try { BitmapFactory.decodeFile(file(a).path) } catch (_: Throwable) { null }
        full = b
        return b
    }

    /** The wallpaper as a full-screen background view, or null when none is set. */
    fun imageView(a: Activity): ImageView? {
        val b = full(a) ?: return null
        val v = ImageView(a)
        v.scaleType = ImageView.ScaleType.CENTER_CROP
        v.setImageBitmap(b)
        return v
    }

    /** A blurred, screen-shaped copy (a small bitmap, stretched when drawn). */
    private fun blurred(a: Activity): Bitmap? {
        val m = a.resources.displayMetrics
        val key = m.widthPixels.toString() + "x" + m.heightPixels
        val cached = blurred
        if (cached != null && blurKey == key) return cached
        val src = full(a) ?: return null
        val aspect = m.widthPixels.toFloat() / m.heightPixels
        var cw = src.width
        var ch = src.height
        if (src.width.toFloat() / src.height > aspect) cw = (src.height * aspect).toInt() else ch = (src.width / aspect).toInt()
        val x = (src.width - cw) / 2
        val y = (src.height - ch) / 2
        val sw = Math.max(m.widthPixels / 8, 16)
        val sh = Math.max(m.heightPixels / 8, 16)
        val small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        Canvas(small).drawBitmap(src, Rect(x, y, x + cw, y + ch), Rect(0, 0, sw, sh), Paint(Paint.FILTER_BITMAP_FLAG))
        blur(small, 4, 3)
        blurred = small
        blurKey = key
        return small
    }

    /** A card background that shows the blurred wallpaper exactly where the card sits on screen, plus the glass tint. */
    fun frost(a: Activity, v: View, radius: Float): Drawable? {
        val b = blurred(a) ?: return null
        return FrostDrawable(v, b, radius)
    }

    private fun blur(b: Bitmap, r: Int, passes: Int) {
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        val tmp = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        for (i in 0 until passes) {
            line(px, tmp, w, h, r, true)
            line(tmp, px, w, h, r, false)
        }
        b.setPixels(px, 0, w, 0, 0, w, h)
    }

    /** One box-blur pass along rows (horizontal) or columns. */
    private fun line(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val count = 2 * r + 1
        for (l in 0 until lines) {
            var ra = 0
            var ga = 0
            var ba = 0
            for (j in -r..r) {
                val i = j.coerceIn(0, len - 1)
                val p = src[if (horizontal) l * w + i else i * w + l]
                ra += (p ushr 16) and 255
                ga += (p ushr 8) and 255
                ba += p and 255
            }
            for (i in 0 until len) {
                dst[if (horizontal) l * w + i else i * w + l] = (0xFF shl 24) or ((ra / count) shl 16) or ((ga / count) shl 8) or (ba / count)
                val o = (i - r).coerceIn(0, len - 1)
                val n = (i + r + 1).coerceIn(0, len - 1)
                val po = src[if (horizontal) l * w + o else o * w + l]
                val pn = src[if (horizontal) l * w + n else n * w + l]
                ra += ((pn ushr 16) and 255) - ((po ushr 16) and 255)
                ga += ((pn ushr 8) and 255) - ((po ushr 8) and 255)
                ba += (pn and 255) - (po and 255)
            }
        }
    }
}

/** Draws the blurred wallpaper at the card's place on screen, clipped to the card, then the glass tint over it. */
private class FrostDrawable(private val v: View, private val blur: Bitmap, private val radius: Float) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val dst = RectF()
    private val loc = IntArray(2)
    private val tint = Glass.drawable(radius)

    override fun draw(c: Canvas) {
        val b = bounds
        rect.set(b)
        path.reset()
        path.addRoundRect(rect, radius, radius, Path.Direction.CW)
        val root = v.rootView
        c.save()
        c.clipPath(path)
        v.getLocationInWindow(loc)
        c.translate(b.left - loc[0].toFloat(), b.top - loc[1].toFloat())
        dst.set(0f, 0f, root.width.toFloat(), root.height.toFloat())
        c.drawBitmap(blur, null, dst, paint)
        c.restore()
        tint.setBounds(b)
        tint.draw(c)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Hosts the system picture chooser (a plugin has no onActivityResult of its own, so a fragment catches the answer). */
@Suppress("DEPRECATION")
internal class WallpaperPick : android.app.Fragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "image/*"
        try {
            startActivityForResult(i, 4721)
        } catch (_: Throwable) {
            finish(false)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != 4721) return
        val uri = data?.data
        val act = activity
        val ok = resultCode == Activity.RESULT_OK && uri != null && act != null && Wallpaper.save(act, uri)
        finish(ok)
    }

    private fun finish(ok: Boolean) {
        try {
            fragmentManager.beginTransaction().remove(this).commitAllowingStateLoss()
        } catch (_: Throwable) {
        }
        val cb = Wallpaper.callback
        Wallpaper.callback = null
        cb?.invoke(ok)
    }
}
