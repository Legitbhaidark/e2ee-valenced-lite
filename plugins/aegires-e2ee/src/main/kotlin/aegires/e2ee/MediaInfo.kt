package aegires.e2ee

import android.content.Context
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri

/**
 * Pixel size of a file from Android's own decoders. Only the header is read, the image is never
 * fully decoded. The source is a plain path or a content:// uri (picked files). Image part adapted
 * from the Aegires app's BitmapDecoder.
 */
internal object MediaInfo {
    private const val MAX_PIXELS = 200_000_000L

    private fun isUri(src: String) = src.startsWith("content://")

    /** "W,H" for an image or video, or "" when it cannot be read. */
    fun probe(ctx: Context?, src: String, kind: String): String =
        try {
            if (kind == "video") video(ctx, src) else image(ctx, src)
        } catch (_: Throwable) {
            ""
        }

    private fun image(ctx: Context?, src: String): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (isUri(src)) {
            val stream = ctx?.contentResolver?.openInputStream(Uri.parse(src)) ?: return ""
            stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        } else {
            BitmapFactory.decodeFile(src, bounds)
        }
        var w = bounds.outWidth
        var h = bounds.outHeight
        if (w <= 0 || h <= 0 || w.toLong() * h > MAX_PIXELS) return ""
        val orientation =
            try {
                if (isUri(src)) {
                    val stream = ctx?.contentResolver?.openInputStream(Uri.parse(src))
                    if (stream == null) {
                        ExifInterface.ORIENTATION_NORMAL
                    } else {
                        stream.use {
                            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                        }
                    }
                } else {
                    ExifInterface(src).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }
            } catch (_: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }
        // These four orientations turn the picture sideways, so width and height swap.
        if (orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
            orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            orientation == ExifInterface.ORIENTATION_TRANSVERSE
        ) {
            val t = w
            w = h
            h = t
        }
        return "$w,$h"
    }

    private fun video(ctx: Context?, src: String): String {
        val r = MediaMetadataRetriever()
        try {
            if (isUri(src)) {
                if (ctx == null) return ""
                r.setDataSource(ctx, Uri.parse(src))
            } else {
                r.setDataSource(src)
            }
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (w <= 0 || h <= 0) return ""
            if (rotation == 90 || rotation == 270) {
                val t = w
                w = h
                h = t
            }
            return "$w,$h"
        } finally {
            try {
                r.release()
            } catch (_: Exception) {
            }
        }
    }
}
