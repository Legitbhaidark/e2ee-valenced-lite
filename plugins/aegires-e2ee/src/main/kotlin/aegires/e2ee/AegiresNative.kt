@file:JvmName("AegiresNative")

package aegires.e2ee

import app.valenced.bridge.asDelegate
import app.valenced.plugins.plugin
import app.valenced.xposed.api.registerNativeMethod

/**
 * Native half of the Aegires E2EE plugin (`dist.android.class` in manifest.json).
 *
 * It only exposes pure functions that the JS side calls with `callNativeMethod`. If this half is
 * missing or a call fails, the JS side silently falls back to its own (slower) JS crypto, which
 * produces identical bytes. The real logic lives in [NativeOps], which has unit tests.
 *
 * Method names are fixed ("aegires.*") and must match js/engine/engine.ts.
 */
@Suppress("UNUSED")
val aegiresNative = plugin {
    start {
        log.i("Aegires native crypto loaded (${manifest.id})")

        // The JS side calls this once to find out whether the native half is installed.
        registerNativeMethod("aegires.ping") { _ -> "pong" }

        // All arguments are strings: password and salt as base64, the numbers in decimal.
        registerNativeMethod("aegires.argon2id") { rawArgs ->
            val args = rawArgs.asDelegate()
            val password: String by args.string()
            val salt: String by args.string()
            val passes: String by args.string()
            val memoryKiB: String by args.string()
            val outLen: String by args.string()

            // The last expression is the return value: the derived key as base64.
            NativeOps.argon2id(password, salt, passes, memoryKiB, outLen)
        }
    }

    stop {
        log.i("Aegires native crypto unloaded")
    }
}
