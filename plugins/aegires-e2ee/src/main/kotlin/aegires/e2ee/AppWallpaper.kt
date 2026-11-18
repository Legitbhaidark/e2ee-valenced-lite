package aegires.e2ee

import android.app.Activity
import android.app.Application
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import java.lang.ref.WeakReference

/**
 * The wallpaper behind the messages in Discord chats (Settings > Appearance), using the same picture as the settings
 * screen. Discord's screens are React Native views that paint their own opaque backgrounds, and those
 * views must not be touched, so the picture cannot go behind them. Instead one see-through layer is put
 * on the window, directly above Discord's content and below the E2EE button and players, and the
 * picture is blended in with "screen": dark areas (Discord's backgrounds) take on the picture, bright
 * ones (text, avatars, images) stay as they are. The layer never takes a touch, never changes a Discord
 * view and is only drawn, so it is cheap and cannot break a screen; remove it and nothing is left behind.
 */
internal object AppWallpaper {
    @Volatile private var on = false
    @Volatile private var blur = false
    @Volatile private var strength = 2 // 1 subtle, 2 medium, 3 strong

    private var layer: WallpaperLayer? = null
    private var host: WeakReference<Activity>? = null
    private var lifecycle: Application.ActivityLifecycleCallbacks? = null
    private var app: Application? = null

    fun alphaFor(level: Int): Int = when (level) {
        1 -> 46
        3 -> 128
        else -> 82
    }

    /** New settings (from the JS side). [a] is the app's activity, if there is one yet. */
    fun configure(newOn: Boolean, newBlur: Boolean, newStrength: Int, a: Activity?) {
        on = newOn
        blur = newBlur
        strength = newStrength.coerceIn(1, 3)
        if (a != null) refresh(a)
    }

    /** Puts the layer on the window, updates it, or removes it, to match the settings and whether a picture exists. */
    fun refresh(a: Activity) {
        a.runOnUiThread {
            try {
                if (!on || !Wallpaper.has(a)) {
                    detach()
                    return@runOnUiThread
                }
                watchLifecycle(a)
                attach(a)
                layer?.update(blur, alphaFor(strength))
            } catch (_: Throwable) {
                detach() // a cosmetic layer must never crash the app
            }
        }
    }

    private fun attach(a: Activity) {
        val cur = layer
        if (cur != null && host?.get() === a && cur.parent != null) return
        detach()
        val decor = a.window.decorView as? ViewGroup ?: return
        val v = WallpaperLayer(a)
        // Index 1: above Discord's content (child 0), below anything added later (E2EE button, players).
        decor.addView(v, Math.min(1, decor.childCount), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        layer = v
        host = WeakReference(a)
    }

    private fun detach() {
        val v = layer
        try {
            (v?.parent as? ViewGroup)?.removeView(v)
        } catch (_: Throwable) {
        }
        layer = null
        host = null
    }

    /** Coming back from another screen the window may have been rebuilt: put the layer back. */
    private fun watchLifecycle(a: Activity) {
        if (lifecycle != null) return
        val application = a.application ?: return
        val cb = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(x: Activity) {
                if (on && host?.get() === x) refresh(x)
            }

            override fun onActivityDestroyed(x: Activity) {
                if (host?.get() === x) detach()
            }

            override fun onActivityCreated(x: Activity, b: Bundle?) {}
            override fun onActivityStarted(x: Activity) {}
            override fun onActivityPaused(x: Activity) {}
            override fun onActivityStopped(x: Activity) {}
            override fun onActivitySaveInstanceState(x: Activity, b: Bundle) {}
        }
        application.registerActivityLifecycleCallbacks(cb)
        lifecycle = cb
        app = application
    }

    /** Plugin stop. */
    fun shutdown() {
        try {
            lifecycle?.let { app?.unregisterActivityLifecycleCallbacks(it) }
        } catch (_: Throwable) {
        }
        lifecycle = null
        app = null
        val v = layer
        if (v != null) v.post { detach() } else detach()
    }
}

/**
 * The layer itself: draws the wallpaper (centre-cropped to the window) with the "screen" blend, but only
 * in the chat's message area, between the header and the message box. On every other screen (DM list,
 * servers, settings) nothing is drawn. Never takes a touch.
 */
private class WallpaperLayer(private val act: Activity) : View(act) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val dst = RectF()
    private val region = Rect()
    private var blur = false
    private val ticker = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            track()
            postDelayed(this, 400)
        }
    }

    init {
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        isClickable = false
        isFocusable = false
        isLongClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
    }

    fun update(newBlur: Boolean, alpha: Int) {
        blur = newBlur
        paint.alpha = alpha
        track()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(ticker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        track()
    }

    /** Where the chat's messages are right now: under the header's search icon, above the message box. Empty when this is not a chat. */
    private fun track() {
        val top = EncryptButton.headerBottom(act)
        val bottom = ChatViewport.composerTop(act)
        val next = Rect()
        if (top >= 0 && bottom != Int.MAX_VALUE && bottom > top && width > 0) next.set(0, top, width, Math.min(bottom, height))
        if (next != region) {
            region.set(next)
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (region.isEmpty) return
        val bmp = Wallpaper.layerBitmap(act, blur) ?: return
        // The picture is cropped for the whole window and only shown in the message area, so it stays
        // still when the keyboard opens or the box grows.
        Wallpaper.cropFor(bmp, width, height, src)
        dst.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.save()
        canvas.clipRect(region)
        canvas.drawBitmap(bmp, src, dst, paint)
        canvas.restore()
    }
}
