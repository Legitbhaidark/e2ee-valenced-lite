@file:JvmName("AegiresNative")

package aegires.e2ee

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
 * Method names are fixed ("aegires.*") and must match js/engine/engine.ts.
 */
@Suppress("UNUSED")
val aegiresNative = plugin {
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
                    File(ctx.codeCacheDir, "aegires-native"),
                    Build.SUPPORTED_ABIS,
                )
            } catch (t: Throwable) {
                log.i("C Argon2id not loaded: ${t.javaClass.simpleName}")
            }
            log.i("Argon2id engine: ${NativeArgon2.status()}")
        }

        // The JS side calls this once to find out whether the native half is installed.
        registerNativeMethod("aegires.ping") { _ -> "pong" }

        // "c:<abi>" when Argon2id runs in the C library, otherwise "java:<why not>" (for Diagnostics).
        // Developer use: same as argon2id but with the engine chosen by the caller ("auto", "c", "java").
        registerNativeAsyncMethod("aegires.argon2with") { rawArgs ->
            val args = rawArgs.asDelegate()
            val engine: String by args.string()
            val password: String by args.string()
            val salt: String by args.string()
            val passes: String by args.string()
            val memoryKiB: String by args.string()
            val outLen: String by args.string()
            NativeOps.argon2idWith(engine, password, salt, passes, memoryKiB, outLen)
        }

        // Settings > Crypto benchmark: the whole report text. The JS side adds only its own plain JS times
        // (10 strings: memory in MiB, then time ms / output head / reason for Argon2id, HKDF and AES).
        registerNativeAsyncMethod("aegires.cryptobench") { rawArgs ->
            val args = rawArgs.asDelegate()
            val memMiB: String by args.string()
            val argonMs: String by args.string()
            val argonHead: String by args.string()
            val argonWhy: String by args.string()
            val hkdfMs: String by args.string()
            val hkdfHead: String by args.string()
            val hkdfWhy: String by args.string()
            val aesMs: String by args.string()
            val aesHead: String by args.string()
            val aesWhy: String by args.string()
            CryptoBench.report(memMiB.toInt(), arrayOf(argonMs, argonHead, argonWhy, hkdfMs, hkdfHead, hkdfWhy, aesMs, aesHead, aesWhy))
        }

        registerNativeMethod("aegires.argon2engine") { _ -> NativeOps.argon2Engine() }

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

        // Picture for a decrypted video (first frame + play button + length) or audio file (a card),
        // written as a JPEG under .../aegires/. Returns "W,H".
        registerNativeAsyncMethod("aegires.mediathumb") { rawArgs ->
            val args = rawArgs.asDelegate()
            val kind: String by args.string()
            val src: String by args.string()
            val outPath: String by args.string()
            val label: String by args.string()
            MediaThumb.make(kind, src, outPath, label)
        }

        // Picture shown in place of a file that is not decrypted yet ("Tap to decrypt" or a progress ring).
        // percent -1 = unknown, sizeBytes 0 = unknown. Written as a JPEG under .../aegires/. Returns "W,H".
        registerNativeAsyncMethod("aegires.placeholder") { rawArgs ->
            val args = rawArgs.asDelegate()
            val kind: String by args.string()
            val state: String by args.string()
            val percent: String by args.string()
            val sizeBytes: String by args.string()
            val outPath: String by args.string()
            MediaThumb.placeholder(kind, state, percent.toIntOrNull() ?: -1, sizeBytes.toLongOrNull() ?: 0L, outPath)
        }

        // "read,total" bytes of the decryption into outPath that is running right now, "" if none.
        registerNativeMethod("aegires.progress") { rawArgs ->
            val args = rawArgs.asDelegate()
            val outPath: String by args.string()
            if (!VideoPlayer.allowed(outPath)) throw IllegalArgumentException("path not allowed")
            FileOps.progress(outPath)
        }

        // Playback of a decrypted video or audio file: in the chat when mode is "inline", else full screen. Returns "ok".
        registerNativeMethod("aegires.playmedia") { rawArgs ->
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
        registerNativeMethod("aegires.touchspy") { _ ->
            withAppActivity { TouchSpy.install(it) }
            "ok"
        }

        // Liquid glass look: "0" off, "1" glass, "2" soft glass, see Glass.kt. Returns "ok".
        registerNativeMethod("aegires.glass") { rawArgs ->
            val args = rawArgs.asDelegate()
            val on: String by args.string()
            Glass.on = on == "1" || on == "2"
            Glass.soft = on == "2"
            "ok"
        }

        // Wallpaper behind Discord itself: args on ("0"/"1"), blur ("0"/"1"), strength ("1".."3"), see AppWallpaper.kt. Returns "ok".
        registerNativeMethod("aegires.appwallpaper") { rawArgs ->
            val args = rawArgs.asDelegate()
            val on: String by args.string()
            val blur: String by args.string()
            val strength: String by args.string()
            withAppActivity { AppWallpaper.configure(on == "1", blur == "1", strength.toIntOrNull() ?: 2, it) }
            "ok"
        }

        // What the in-chat player did for the last tap, for Diagnostics.
        registerNativeMethod("aegires.inlinestatus") { _ -> InlineVideo.status() }

        // The "E2EE" on/off button next to the search icon. State: on | off | locked | hide. Returns "ok".
        registerNativeMethod("aegires.encbutton") { rawArgs ->
            val args = rawArgs.asDelegate()
            val state: String by args.string()
            withAppActivity { EncryptButton.set(it, state) }
            "ok"
        }

        // A short Android toast (Discord's own toast can crash the app, see js/notify.ts). Returns "ok".
        registerNativeMethod("aegires.toast") { rawArgs ->
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
        registerNativeMethod("aegires.encbuttonstatus") { _ -> EncryptButton.status() }

        // How many times the button was pressed since the last call.
        registerNativeMethod("aegires.encbuttontaps") { _ -> EncryptButton.takeTaps().toString() }

        // Link preview data (title, description, picture) for one https url, as JSON; "" if none.
        registerNativeAsyncMethod("aegires.linkpreview") { rawArgs ->
            val args = rawArgs.asDelegate()
            val url: String by args.string()
            LinkPreview.fetch(url)
        }

        // The settings screen (SettingsScreen.kt): open it, change what it shows, and poll what the user did.
        registerNativeMethod("aegires.settingsopen") { rawArgs ->
            val args = rawArgs.asDelegate()
            val json: String by args.string()
            withAppActivity { SettingsScreen.show(it, json) }
            "ok"
        }

        registerNativeMethod("aegires.settingsupdate") { rawArgs ->
            val args = rawArgs.asDelegate()
            val json: String by args.string()
            SettingsScreen.update(json)
            "ok"
        }

        registerNativeMethod("aegires.settingstake") { _ -> SettingsScreen.take() }

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
        SettingsScreen.close()
        VideoPlayer.close()
        InlineVideo.close()
        InlineAudio.close()
        TouchSpy.shutdown()
        EncryptButton.shutdown()
        AppWallpaper.shutdown()
        log.i("Aegires native crypto unloaded")
    }
}
