package aegires.e2ee

import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaMetadataRetriever

/**
 * Pixel size of a decrypted file from Android's own decoders. Only the header is read, the image
 * is never fully decoded. Image part adapted from the Aegires app's BitmapDecoder.
 */
internal object MediaInfo {
    private const val MAX_PIXELS = 200_000_000L

    /** "W,H" for an image or video file, or "" when it cannot be read. */
    fun probe(path: String, kind: String): String =
        try {
            if (kind == "video") video(path) else image(path)
        } catch (_: Throwable) {
            ""
        }

    private fun image(path: String): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var w = bounds.outWidth
        var h = bounds.outHeight
        if (w <= 0 || h <= 0 || w.toLong() * h > MAX_PIXELS) return ""
        val orientation =
            try {
                ExifInterface(path).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
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

    private fun video(path: String): String {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(path)
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
