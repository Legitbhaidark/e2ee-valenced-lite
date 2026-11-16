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

    // ------------------------------------------------------------------ placeholder

    /**
     * The picture shown in place of a file that is not decrypted yet: a dark "skeleton" card with a
     * round badge and two lines of text. [state] "tap" = waiting for a tap (download arrow),
     * "work" = decrypting (progress ring with the percentage inside; [percent] -1 = unknown).
     * [sizeBytes] is the encrypted file size, 0 = unknown. Writes a JPEG and returns "W,H".
     */
    fun placeholder(kind: String, state: String, percent: Int, sizeBytes: Long, out: String): String {
        if (!VideoPlayer.allowed(out)) throw IllegalArgumentException("path not allowed")
        val w = if (kind == "audio") CARD_W else 640
        val h = if (kind == "audio") CARD_H else 360
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            val c = Canvas(bmp)
            c.drawColor(0xFF1E1F22.toInt())

            // Skeleton blocks: faint bars like a loading card.
            val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2B2D31.toInt() }
            c.drawRoundRect(RectF(w * 0.06f, h * 0.08f, w * 0.46f, h * 0.08f + h * 0.05f), 12f, 12f, bar)
            c.drawRoundRect(RectF(w * 0.06f, h * 0.16f, w * 0.30f, h * 0.16f + h * 0.04f), 10f, 10f, bar)

            val label = when (kind) {
                "video" -> "video"
                "audio" -> "audio"
                else -> "image"
            }
            val cx = if (kind == "audio") 108f else w / 2f
            val cy = if (kind == "audio") h / 2f else h * 0.42f
            val radius = if (kind == "audio") 56f else minOf(w, h) * 0.17f

            // Round badge with a ring.
            c.drawCircle(cx, cy, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2B2D31.toInt() })
            val ringW = radius * 0.16f
            val ringRect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
            ringRect.inset(ringW / 2f, ringW / 2f)
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = ringW
                color = 0xFF3F4147.toInt()
            }
            c.drawArc(ringRect, 0f, 360f, false, ring)
            val accent = 0xFF5865F2.toInt()
            if (state == "work") {
                ring.color = accent
                ring.strokeCap = Paint.Cap.ROUND
                val sweep = if (percent < 0) 90f else 360f * percent.coerceIn(0, 100) / 100f
                c.drawArc(ringRect, -90f, maxOf(sweep, 4f), false, ring)
                val pp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = radius * 0.62f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textAlign = Paint.Align.CENTER
                }
                val txt = if (percent < 0) "\u2026" else "$percent%"
                c.drawText(txt, cx, cy - (pp.ascent() + pp.descent()) / 2f, pp)
            } else {
                // "tap" state: a down arrow into a tray.
                val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.STROKE
                    strokeWidth = radius * 0.13f
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }
                val s = radius * 0.42f
                val p = Path().apply {
                    moveTo(cx, cy - s)
                    lineTo(cx, cy + s * 0.55f)
                    moveTo(cx - s * 0.62f, cy - s * 0.05f)
                    lineTo(cx, cy + s * 0.6f)
                    lineTo(cx + s * 0.62f, cy - s * 0.05f)
                    moveTo(cx - s * 0.8f, cy + s * 1.0f)
                    lineTo(cx + s * 0.8f, cy + s * 1.0f)
                }
                c.drawPath(p, arrow)
            }

            // Text.
            val title = if (state == "work") "Decrypting $label\u2026" else "Tap to decrypt"
            val sub = if (sizeBytes > 0) "Encrypted $label \u00B7 ${formatSize(sizeBytes)}" else "Encrypted $label"
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = if (kind == "audio") 36f else h * 0.075f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val sp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFB5BAC1.toInt()
                textSize = if (kind == "audio") 27f else h * 0.058f
            }
            if (kind == "audio") {
                val left = 200f
                val maxText = w - left - 28f
                c.drawText(TextUtils.ellipsize(title, tp, maxText, TextUtils.TruncateAt.END).toString(), left, cy - 16f, tp)
                c.drawText(TextUtils.ellipsize(sub, sp, maxText, TextUtils.TruncateAt.END).toString(), left, cy + 38f, sp)
            } else {
                tp.textAlign = Paint.Align.CENTER
                sp.textAlign = Paint.Align.CENTER
                c.drawText(title, w / 2f, h * 0.78f, tp)
                c.drawText(sub, w / 2f, h * 0.78f + h * 0.095f, sp)
            }

            File(out).parentFile?.mkdirs()
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            return "${bmp.width},${bmp.height}"
        } finally {
            bmp.recycle()
        }
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
