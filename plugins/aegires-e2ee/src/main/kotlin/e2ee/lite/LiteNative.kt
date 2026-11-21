@file:JvmName("LiteNative")

package e2ee.lite

import android.content.Context
import android.widget.Toast
import android.net.Uri
import android.os.Build
import app.valenced.bridge.asDelegate
import app.valenced.plugins.plugin
import app.valenced.xposed.api.registerNativeAsyncMethod
import app.valenced.xposed.api.registerNativeMethod
import java.io.File

/**
 * Native half of the Aegires E2EE plugin (`dist.android.class` in manifest.json).
 *
 * It only exposes pure functions that the JS side calls with `callNativeMethod`. If this half is
 * missing or a call fails, the JS side silently falls back to its own (slower) JS crypto, which
 * produces identical bytes. The real logic lives in [NativeOps], which has unit tests.
 *
 * Method names are fixed ("e2eelite.*") and must match js/engine/engine.ts.
 */
@Suppress("UNUSED")
val liteNative = plugin {
    start {
        log.i("Aegires native crypto loaded (${manifest.id})")

        // The app's Context, to read content:// uris (picked files) as streams instead of through JS.
        var appContext: Context? = null
        withAppContext { ctx ->
            appContext = ctx
            // Optional C Argon2id, packed in plugin.jar as lib/<abi>/libaegirescrypto.so. Any problem
            // leaves the Java Argon2id in charge (same bytes); see NativeArgon2.status().
            try {
                NativeArgon2.load(
                    NativeArgon2::class.java.classLoader,
                    File(ctx.codeCacheDir, "e2ee-lite-native"),
                    Build.SUPPORTED_ABIS,
                )
            } catch (t: Throwable) {
                log.i("C Argon2id not loaded: ${t.javaClass.simpleName}")
            }
            log.i("Argon2id engine: ${NativeArgon2.status()}")
        }

        // The JS side calls this once to find out whether the native half is installed.
        registerNativeMethod("e2eelite.ping") { _ -> "pong" }

        // "c:<abi>" when Argon2id runs in the C library, otherwise "java:<why not>" (for Diagnostics).
        // Developer use: same as argon2id but with the engine chosen by the caller ("auto", "c", "java").
        registerNativeAsyncMethod("e2eelite.argon2with") { rawArgs ->
            val args = rawArgs.asDelegate()
            val engine: String by args.string()
            val password: String by args.string()
            val salt: String by args.string()
            val passes: String by args.string()
            val memoryKiB: String by args.string()
            val outLen: String by args.string()
            NativeOps.argon2idWith(engine, password, salt, passes, memoryKiB, outLen)
        }

        registerNativeMethod("e2eelite.argon2engine") { _ -> NativeOps.argon2Engine() }

        // All arguments are strings: password and salt as base64, the numbers in decimal.
        // Async: the 64 MiB derivation runs on a background dispatcher, so it never blocks the
        // React native-modules thread (which would freeze Discord's other native calls for seconds).
        registerNativeAsyncMethod("e2eelite.argon2id") { rawArgs ->
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
        registerNativeAsyncMethod("e2eelite.v2encrypt") { rawArgs ->
            val args = rawArgs.asDelegate()
            val master: String by args.string()
            val preset: String by args.string()
            val channelId: String by args.string()
            val senderId: String by args.string()
            val plain: String by args.string()
            NativeOps.v2Encrypt(master, preset, channelId, senderId, plain)
        }

        registerNativeAsyncMethod("e2eelite.v2decrypt") { rawArgs ->
            val args = rawArgs.asDelegate()
            val master: String by args.string()
            val channelId: String by args.string()
            val senderId: String by args.string()
            val wire: String by args.string()
            NativeOps.v2Decrypt(master, channelId, senderId, wire)
        }

        // File encryption through the Java FileOps (Aegires v3 file format). All strings.
        registerNativeAsyncMethod("e2eelite.sealfile") { rawArgs ->
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
        registerNativeAsyncMethod("e2eelite.sealbytes") { rawArgs ->
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
        registerNativeAsyncMethod("e2eelite.randomname") { rawArgs ->
            val args = rawArgs.asDelegate()
            val original: String by args.string()
            val mime: String by args.string()
            FileNames.encryptedName(original, mime)
        }

        // Downloads a Discord CDN url (or reads a local file) and decrypts it into outPath. All strings.
        registerNativeAsyncMethod("e2eelite.openfile") { rawArgs ->
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
        registerNativeAsyncMethod("e2eelite.mediainfo") { rawArgs ->
            val args = rawArgs.asDelegate()
            val path: String by args.string()
            val kind: String by args.string()
            MediaInfo.probe(appContext, path, kind)
        }

        // Picture for a decrypted video (first frame + play button + length) or audio file (a card),
        // written as a JPEG under .../e2ee-lite/. Returns "W,H".
        registerNativeAsyncMethod("e2eelite.mediathumb") { rawArgs ->
            val args = rawArgs.asDelegate()
            val kind: String by args.string()
            val src: String by args.string()
            val outPath: String by args.string()
            val label: String by args.string()
            MediaThumb.make(kind, src, outPath, label)
        }

        // Picture shown in place of a file that is not decrypted yet ("Tap to decrypt" or a progress ring).
        // percent -1 = unknown, sizeBytes 0 = unknown. Written as a JPEG under .../e2ee-lite/. Returns "W,H".
        registerNativeAsyncMethod("e2eelite.placeholder") { rawArgs ->
            val args = rawArgs.asDelegate()
            val kind: String by args.string()
            val state: String by args.string()
            val percent: String by args.string()
            val sizeBytes: String by args.string()
            val outPath: String by args.string()
            MediaThumb.placeholder(kind, state, percent.toIntOrNull() ?: -1, sizeBytes.toLongOrNull() ?: 0L, outPath)
        }

        // "read,total" bytes of the decryption into outPath that is running right now, "" if none.
        registerNativeMethod("e2eelite.progress") { rawArgs ->
            val args = rawArgs.asDelegate()
            val outPath: String by args.string()
            if (!VideoPlayer.allowed(outPath)) throw IllegalArgumentException("path not allowed")
            FileOps.progress(outPath)
        }

        // Playback of a decrypted video or audio file: in the chat when mode is "inline", else full screen. Returns "ok".
        registerNativeMethod("e2eelite.playmedia") { rawArgs ->
            val args = rawArgs.asDelegate()
            val path: String by args.string()
            val title: String by args.string()
            val mode: String by args.string() // "inline" = play in the chat (video and audio), anything else = full screen
            val vw: String by args.string() // the picture's pixel size, to pick the right view (0 = unknown)
            val vh: String by args.string()
            if (!VideoPlayer.allowed(path)) throw IllegalArgumentException("path not allowed")
            withAppActivity {
                InlineVideo.note("not tried: full-screen mode")
                if (mode == "inline" && VideoPlayer.isAudio(path)) {
                    InlineAudio.start(it, path, title, vw.toIntOrNull() ?: 0, vh.toIntOrNull() ?: 0)
                } else if (mode == "inline") {
                    InlineVideo.start(it, path, title, vw.toIntOrNull() ?: 0, vh.toIntOrNull() ?: 0)
                } else {
                    VideoPlayer.show(it, path, title)
                }
            }
            "ok"
        }

        // The spot of the last touch is needed to find the picture a video plays on. The JS side calls
        // this once the app's UI is up (the activity may not exist yet while the plugin starts).
        registerNativeMethod("e2eelite.touchspy") { _ ->
            withAppActivity { TouchSpy.install(it) }
            "ok"
        }

        // What the in-chat player did for the last tap, for Diagnostics.
        registerNativeMethod("e2eelite.inlinestatus") { _ -> InlineVideo.status() }

        // The "E2EE" on/off button next to the search icon. State: on | off | locked | hide. Returns "ok".
        registerNativeMethod("e2eelite.encbutton") { rawArgs ->
            val args = rawArgs.asDelegate()
            val state: String by args.string()
            withAppActivity { EncryptButton.set(it, state) }
            "ok"
        }

        // A short Android toast (Discord's own toast can crash the app, see js/notify.ts). Returns "ok".
        registerNativeMethod("e2eelite.toast") { rawArgs ->
            val args = rawArgs.asDelegate()
            val text: String by args.string()
            withAppActivity { act ->
                act.runOnUiThread {
                    try {
                        Toast.makeText(act, text, Toast.LENGTH_LONG).show()
                    } catch (_: Throwable) {
                    }
                }
            }
            "ok"
        }

        // Why the button is shown or hidden (for Diagnostics).
        registerNativeMethod("e2eelite.encbuttonstatus") { _ -> EncryptButton.status() }

        // How many times the button was pressed since the last call.
        registerNativeMethod("e2eelite.encbuttontaps") { _ -> EncryptButton.takeTaps().toString() }

        // Full-screen playback of a decrypted video file (path under .../e2ee-lite/). Returns "ok".
        registerNativeMethod("e2eelite.playvideo") { rawArgs ->
            val args = rawArgs.asDelegate()
            val path: String by args.string()
            if (!VideoPlayer.allowed(path)) throw IllegalArgumentException("path not allowed")
            withAppActivity { VideoPlayer.show(it, path) }
            "ok"
        }
    }

    stop {
        VideoPlayer.close()
        InlineVideo.close()
        InlineAudio.close()
        TouchSpy.shutdown()
        EncryptButton.shutdown()
        log.i("Aegires native crypto unloaded")
    }
}
