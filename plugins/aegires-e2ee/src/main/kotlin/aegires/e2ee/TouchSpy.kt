package aegires.e2ee

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.MotionEvent
import android.view.Window

/**
 * Remembers where the screen was last touched (finger down). The JS side only learns THAT a video
 * picture was tapped, not WHERE; [InlineVideo] needs the spot to find the picture's view and play
 * the video in place. It looks at touches only to note the coordinates; every event is passed on
 * unchanged, nothing is stored beyond the last position, and it is never sent anywhere.
 */
internal object TouchSpy {
    @Volatile var x = -1f
    @Volatile var y = -1f
    @Volatile var at = 0L
    @Volatile private var enabled = false
    @Volatile private var touches = 0
    @Volatile private var windows = 0

    private var app: Application? = null
    private var callbacks: Application.ActivityLifecycleCallbacks? = null

    /** For Diagnostics. */
    fun describe(): String =
        "touch spy " + (if (enabled) "on" else "off") + ", " + windows + " window(s) wrapped, " + touches + " touch(es) seen" +
            (if (x >= 0f) ", last " + (System.currentTimeMillis() - at) + " ms ago" else "")

    /** True when a touch happened in the last [ms] milliseconds. */
    fun recent(ms: Long): Boolean = x >= 0f && System.currentTimeMillis() - at <= ms

    fun install(activity: Activity) {
        enabled = true
        wrap(activity)
        if (callbacks != null) return
        val application = activity.application ?: return
        val cb = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) = wrap(a)
            override fun onActivityResumed(a: Activity) = wrap(a)
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        }
        application.registerActivityLifecycleCallbacks(cb)
        callbacks = cb
        app = application
    }

    /** Stops recording (the wrappers stay on the windows but only pass events through). */
    fun shutdown() {
        enabled = false
        try {
            callbacks?.let { app?.unregisterActivityLifecycleCallbacks(it) }
        } catch (_: Throwable) {
        }
        callbacks = null
        app = null
        x = -1f
        y = -1f
    }

    private fun wrap(a: Activity) {
        a.runOnUiThread {
            try {
                val w = a.window ?: return@runOnUiThread
                val cur = w.callback
                if (cur != null && cur !is Spy) {
                    w.callback = Spy(cur)
                    windows++
                }
            } catch (_: Throwable) {
            }
        }
    }

    private class Spy(private val base: Window.Callback) : Window.Callback by base {
        override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
            if (enabled && event != null && event.actionMasked == MotionEvent.ACTION_DOWN) {
                touches++
                x = event.rawX
                y = event.rawY
                at = System.currentTimeMillis()
            }
            return base.dispatchTouchEvent(event)
        }
    }
}
