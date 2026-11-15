@file:JvmName("AegiresNative")

package aegires.e2ee

import android.content.Context
import android.net.Uri
import app.valenced.bridge.asDelegate
import app.valenced.plugins.plugin
import app.valenced.xposed.api.registerNativeAsyncMethod
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

        // The app's Context, to read content:// uris (picked files) as streams instead of through JS.
        var appContext: Context? = null
        withAppContext { appContext = it }

        // The JS side calls this once to find out whether the native half is installed.
        registerNativeMethod("aegires.ping") { _ -> "pong" }

        // All arguments are strings: password and salt as base64, the numbers in decimal.
        // Async: the 64 MiB derivation runs on a background dispatcher, so it never blocks the
        // React native-modules thread (which would freeze Discord's other native calls for seconds).
        registerNativeAsyncMethod("aegires.argon2id") { rawArgs ->
            val args = rawArgs.asDelegate()
            val password: String by args.string()
            val salt: String by args.string()
            val passes: String by args.string()
            val memoryKiB: String by args.string()
            val outLen: String by args.string()

            // The last expression is the return value: the derived key as base64.
            NativeOps.argon2id(password, salt, passes, memoryKiB, outLen)
        }

        // Aegires v2 messages through the Java Crypto copied from the Aegires app. All strings.
        registerNativeAsyncMethod("aegires.v2encrypt") { rawArgs ->
            val args = rawArgs.asDelegate()
            val master: String by args.string()
            val preset: String by args.string()
            val channelId: String by args.string()
            val senderId: String by args.string()
            val plain: String by args.string()
            NativeOps.v2Encrypt(master, preset, channelId, senderId, plain)
        }

        registerNativeAsyncMethod("aegires.v2decrypt") { rawArgs ->
            val args = rawArgs.asDelegate()
            val master: String by args.string()
            val channelId: String by args.string()
            val senderId: String by args.string()
            val wire: String by args.string()
            NativeOps.v2Decrypt(master, channelId, senderId, wire)
        }

        // File encryption through the Java FileOps (Aegires v3 file format). All strings.
        registerNativeAsyncMethod("aegires.sealfile") { rawArgs ->
            val args = rawArgs.asDelegate()
            val master: String by args.string()
            val preset: String by args.string()
            val channelId: String by args.string()
            val senderId: String by args.string()
            val src: String by args.string()
            val outPath: String by args.string()
            val maxBytes: String by args.string()
            val ctx = appContext
            if (src.startsWith("content://") && ctx != null) {
                val input = ctx.contentResolver.openInputStream(Uri.parse(src)) ?: throw IllegalStateException("cannot open file")
                FileOps.sealStream(master, preset, channelId, senderId, input, outPath, maxBytes)
            } else {
                FileOps.seal(master, preset, channelId, senderId, src, outPath, maxBytes)
            }
        }

        // Same, for plaintext that JS already read (base64), e.g. content:// uris.
        registerNativeAsyncMethod("aegires.sealbytes") { rawArgs ->
            val args = rawArgs.asDelegate()
            val master: String by args.string()
            val preset: String by args.string()
            val channelId: String by args.string()
            val senderId: String by args.string()
            val plain: String by args.string()
            val outPath: String by args.string()
            val maxBytes: String by args.string()
            FileOps.sealBytes(master, preset, channelId, senderId, plain, outPath, maxBytes)
        }

        // Random upload name for an encrypted file: "<20 hex>.enc.<ext>".
        registerNativeAsyncMethod("aegires.randomname") { rawArgs ->
            val args = rawArgs.asDelegate()
            val original: String by args.string()
            val mime: String by args.string()
            FileNames.encryptedName(original, mime)
        }

        // Downloads a Discord CDN url (or reads a local file) and decrypts it into outPath. All strings.
        registerNativeAsyncMethod("aegires.openfile") { rawArgs ->
            val args = rawArgs.asDelegate()
            val master: String by args.string()
            val channelId: String by args.string()
            val senderId: String by args.string()
            val src: String by args.string()
            val outPath: String by args.string()
            val maxBytes: String by args.string()
            FileOps.open(master, channelId, senderId, src, outPath, maxBytes)
        }

        // "W,H" of a decrypted image or video file, "" when unknown.
        registerNativeAsyncMethod("aegires.mediainfo") { rawArgs ->
            val args = rawArgs.asDelegate()
            val path: String by args.string()
            val kind: String by args.string()
            MediaInfo.probe(appContext, path, kind)
        }

        // Full-screen playback of a decrypted video file (path under .../aegires/). Returns "ok".
        registerNativeMethod("aegires.playvideo") { rawArgs ->
            val args = rawArgs.asDelegate()
            val path: String by args.string()
            if (!VideoPlayer.allowed(path)) throw IllegalArgumentException("path not allowed")
            withAppActivity { VideoPlayer.show(it, path) }
            "ok"
        }
    }

    stop {
        VideoPlayer.close()
        log.i("Aegires native crypto unloaded")
    }
}
