package aegires.e2ee

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.text.TextPaint
import android.text.TextUtils
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Makes the picture Discord shows in the chat for a decrypted video or audio file.
 *
 * Discord draws the chat natively and cannot play file:// media, so a decrypted video is shown as a
 * picture. To tell people it is a video, the first frame gets a play button and the length drawn
 * on it. A decrypted audio file has no picture at all, so a small "audio card" is drawn instead
 * (play button, title, length, size). Tapping either opens [VideoPlayer].
 *
 * Both files must live under an "aegires" cache folder (same rule as [VideoPlayer]).
 */
internal object MediaThumb {
    private const val MAX_SIDE = 1280
    private const val CARD_W = 720
    private const val CARD_H = 216

    /** Writes a JPEG to [out] and returns its size as "W,H". Throws if it cannot be made. */
    fun make(kind: String, src: String, out: String, label: String): String {
        if (!VideoPlayer.allowed(src) || !VideoPlayer.allowed(out)) throw IllegalArgumentException("path not allowed")
        val bmp = if (kind == "audio") audioCard(src, label) else videoFrame(src)
        try {
            File(out).parentFile?.mkdirs()
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            return "${bmp.width},${bmp.height}"
        } finally {
            bmp.recycle()
        }
    }

    // ------------------------------------------------------------------ video

    private fun videoFrame(path: String): Bitmap {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(path)
            val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            // A frame a moment in avoids the black first frame many videos start with.
            val atUs = if (durMs > 3000) 1_000_000L else 0L
            val raw = r.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: r.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: throw IllegalStateException("no video frame")
            var frame = raw
            val big = maxOf(raw.width, raw.height)
            if (big > MAX_SIDE) {
                val s = MAX_SIDE.toFloat() / big
                frame = Bitmap.createScaledBitmap(raw, maxOf(1, (raw.width * s).toInt()), maxOf(1, (raw.height * s).toInt()), true)
                if (frame !== raw) raw.recycle()
            }
            // Frames from the retriever can be immutable: draw on a copy.
            val bmp = frame.copy(Bitmap.Config.ARGB_8888, true) ?: throw IllegalStateException("cannot copy frame")
            if (bmp !== frame) frame.recycle()
            drawPlayBadge(Canvas(bmp), bmp.width.toFloat(), bmp.height.toFloat())
            if (durMs > 0) drawLength(Canvas(bmp), bmp.width.toFloat(), bmp.height.toFloat(), formatLength(durMs))
            return bmp
        } finally {
            try {
                r.release()
            } catch (_: Exception) {
            }
        }
    }

    /** A round dark button with a white triangle, in the middle. */
    private fun drawPlayBadge(c: Canvas, w: Float, h: Float) {
        val radius = (minOf(w, h) * 0.12f).coerceIn(30f, 110f)
        val cx = w / 2f
        val cy = h / 2f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3000000.toInt() }
        c.drawCircle(cx, cy, radius, fill)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xE6FFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = radius * 0.07f
        }
        c.drawCircle(cx, cy, radius, ring)
        drawTriangle(c, cx + radius * 0.07f, cy, radius * 0.5f, Color.WHITE)
    }

    private fun drawTriangle(c: Canvas, cx: Float, cy: Float, size: Float, argb: Int) {
        val p = Path().apply {
            moveTo(cx - size * 0.45f, cy - size * 0.62f)
            lineTo(cx - size * 0.45f, cy + size * 0.62f)
            lineTo(cx + size * 0.62f, cy)
            close()
        }
        c.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argb })
    }

    /** "0:12" in a dark pill, bottom left. */
    private fun drawLength(c: Canvas, w: Float, h: Float, text: String) {
        val size = (minOf(w, h) * 0.045f).coerceIn(18f, 44f)
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val pad = size * 0.5f
        val tw = tp.measureText(text)
        val left = size * 0.7f
        val bottom = h - size * 0.7f
        val rect = RectF(left, bottom - size - pad, left + tw + pad * 2, bottom)
        c.drawRoundRect(rect, size * 0.5f, size * 0.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3000000.toInt() })
        c.drawText(text, rect.left + pad, rect.bottom - pad * 0.5f - tp.descent(), tp)
    }

    // ------------------------------------------------------------------ audio

    private fun audioCard(path: String, label: String): Bitmap {
        var title = label
        var artist = ""
        var durMs = 0L
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(path)
            durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val t = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            if (!t.isNullOrBlank()) title = t
            artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
        } catch (_: Exception) {
            // Unknown length / tags: the card still draws, with what we have.
        } finally {
            try {
                r.release()
            } catch (_: Exception) {
            }
        }
        if (title.isBlank()) title = "Audio"

        val bmp = Bitmap.createBitmap(CARD_W, CARD_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF1E1F22.toInt())

        // Round play button on the left.
        val cx = 108f
        val cy = CARD_H / 2f
        c.drawCircle(cx, cy, 62f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5865F2.toInt() })
        drawTriangle(c, cx + 4f, cy, 30f, Color.WHITE)

        val left = 200f
        val maxText = CARD_W - left - 28f
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 36f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFB5BAC1.toInt()
            textSize = 27f
        }
        val tShown = TextUtils.ellipsize(title, titlePaint, maxText, TextUtils.TruncateAt.END).toString()
        c.drawText(tShown, left, cy - 16f, titlePaint)

        val parts = ArrayList<String>()
        if (artist.isNotBlank()) parts.add(artist)
        parts.add(if (durMs > 0) "Audio · ${formatLength(durMs)}" else "Audio")
        val bytes = File(path).length()
        if (bytes > 0) parts.add(formatSize(bytes))
        val sub = TextUtils.ellipsize(parts.joinToString(" · "), subPaint, maxText, TextUtils.TruncateAt.END).toString()
        c.drawText(sub, left, cy + 38f, subPaint)
        return bmp
    }

    // ------------------------------------------------------------------ helpers

    fun formatLength(ms: Long): String {
        val s = (ms + 500) / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(Locale.ROOT, "%d:%02d", m, sec)
    }

    private fun formatSize(b: Long): String =
        when {
            b >= 1L shl 30 -> String.format(Locale.ROOT, "%.1f GB", b / (1024.0 * 1024 * 1024))
            b >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MB", b / (1024.0 * 1024))
            b >= 1L shl 10 -> String.format(Locale.ROOT, "%.0f KB", b / 1024.0)
            else -> "$b B"
        }
}
