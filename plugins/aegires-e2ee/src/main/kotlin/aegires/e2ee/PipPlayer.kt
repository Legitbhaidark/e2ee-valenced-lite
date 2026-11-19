package aegires.e2ee

import android.app.Activity
import android.app.PictureInPictureParams
import android.os.Build
import android.util.Rational

/**
 * Android's picture-in-picture, which can only shrink the WHOLE app window ("Whole app" in the PiP
 * choice). Discord is one activity, so there is no way to send just a video there; the "Video only"
 * choice is [MiniPlayer] instead, a small player that floats inside the app.
 */
internal object PipPlayer {
    fun supported(a: Activity): Boolean =
        Build.VERSION.SDK_INT >= 26 && a.packageManager.hasSystemFeature("android.software.picture_in_picture")

    /** Whole-app picture-in-picture: the app window itself shrinks, nothing is put over it. */
    fun enterApp(a: Activity, vw: Int, vh: Int): Boolean {
        if (!supported(a)) return false
        return try {
            val b = PictureInPictureParams.Builder()
            if (vw > 0 && vh > 0) {
                // Android only accepts shapes between 1:2.39 and 2.39:1.
                val r = (vw.toFloat() / vh).coerceIn(0.43f, 2.38f)
                b.setAspectRatio(Rational((r * 1000).toInt(), 1000))
            }
            a.enterPictureInPictureMode(b.build())
        } catch (_: Throwable) {
            false
        }
    }
}
