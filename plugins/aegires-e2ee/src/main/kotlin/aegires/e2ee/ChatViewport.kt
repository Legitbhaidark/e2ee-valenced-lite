package aegires.e2ee

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import java.lang.ref.WeakReference

/**
 * Where the chat's pictures may be seen. Discord draws its message box ABOVE the message list, which
 * keeps scrolling underneath it, so a picture's own visible rect still includes the part hidden behind
 * the box. The in-chat players are separate views on top of everything, so without this they would be
 * drawn over the message box. [visible] cuts them off at the top edge of the message box, which makes
 * them slide away under it exactly like Discord's own attachments.
 */
internal object ChatViewport {
    private var composer: WeakReference<EditText>? = null
    private var scanAt = 0L

    /** Top edge (screen y) of the message box bar; Int.MAX_VALUE when no chat box is on screen. */
    fun composerTop(a: Activity): Int {
        val decor = a.window.decorView as? ViewGroup ?: return Int.MAX_VALUE
        val dm = a.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val r = Rect()
        var et = composer?.get()
        if (et == null || !et.isAttachedToWindow || !et.isShown || !et.getGlobalVisibleRect(r) || r.top < h / 4) {
            et = null
            val now = SystemClock.uptimeMillis()
            if (now - scanAt >= 250) { // a miss is not searched for again on every frame
                scanAt = now
                et = find(decor, h)
                composer = et?.let { WeakReference(it) }
            }
            if (et == null || !et.getGlobalVisibleRect(r)) return Int.MAX_VALUE
        }
        // The bar around the text field: the highest parent that is still bar-shaped (wide, not tall).
        var top = r.top
        var matched = false
        var p = et.parent as? View
        val pr = Rect()
        var depth = 0
        while (p != null && p !== decor && depth < 8) {
            if (!p.getGlobalVisibleRect(pr)) break
            if (pr.width() * 100 < w * 85 || pr.height() * 100 > h * 30 || pr.top < h / 4) break
            top = pr.top
            matched = true
            p = p.parent as? View
            depth++
        }
        if (!matched) top = r.top - (14f * dm.density).toInt()
        return top
    }

    private fun find(v: View, h: Int): EditText? {
        if (v.visibility != View.VISIBLE) return null
        if (v is EditText && v.isShown) {
            val r = Rect()
            if (v.getGlobalVisibleRect(r) && r.top >= h / 4) return v
        }
        if (v is ViewGroup) {
            for (i in v.childCount - 1 downTo 0) {
                val f = find(v.getChildAt(i), h)
                if (f != null) return f
            }
        }
        return null
    }

    /**
     * The part of [pic] that is really on screen, in [out] (screen coordinates): not scrolled off, not
     * behind the message box. False when nothing worth showing is left.
     */
    fun visible(a: Activity, pic: View, out: Rect): Boolean {
        if (!pic.isShown || !pic.getGlobalVisibleRect(out)) return false
        val lim = composerTop(a)
        if (out.bottom > lim) out.bottom = lim
        return out.height() >= (6f * a.resources.displayMetrics.density).toInt() && out.width() > 0
    }
}

/** A player frame that lets touches in its clipped-away part fall through to what is drawn under it (the message box). */
internal class ClipFrame(ctx: Context) : FrameLayout(ctx) {
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val cb = clipBounds
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && cb != null && !cb.contains(ev.x.toInt(), ev.y.toInt())) return false
        return super.dispatchTouchEvent(ev)
    }
}
