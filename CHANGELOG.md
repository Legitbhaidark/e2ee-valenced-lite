# Changelog

Aegires E2EE for Valenced. Newest first. The release workflow uses the `## <version>` section as the
GitHub Release notes for that version; a version without a section gets its commit list instead.
Stable releases come from `main`, `-devN` pre-releases from `dev`.

## 0.1.33-dev2
- The header button is glued to its place: it follows the header icons frame by frame (also while a screen slides) and disappears in the same frame the header is gone.
- The button is not shown when the chat has no key set.
- Messages that are encrypted but cannot be decrypted (wrong key) now get the lock beside the name too.

## 0.1.33-dev1
- The header button now reads "🔒 E2EE" when encryption is on and "🔒 OFF" when it is off.
- Fixed: the button no longer shows in the DM list, only inside a chat.
- Fixed: in a DM the button sits left of the call and video icons and no longer covers the video call icon.
- A short toast after installing or updating the plugin says the button can be tapped to switch encryption on or off; the settings explain it too.

## 0.1.32 - stable
- The C Argon2id library is now built for all four Android CPU types (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`), so every device gets the native speed.
- Plugin zips are also kept as downloadable artifacts on every release run.

## 0.1.32-dev2
- The C Argon2id library is now also built for 32-bit x86 (`x86`). It ships for `arm64-v8a`, `armeabi-v7a`, `x86_64` and `x86`, so every Android CPU gets the native speed.

## 0.1.32-dev1
- Every release run (and every pull request build) also uploads the plugin zips as a workflow artifact, so a zip can be downloaded from the Actions run page without a Release.

## 0.1.31 - stable
- Dropped the v1 message format completely. v1 messages are no longer read or sent; stored v1 keys are deleted on start.
- Setting a key is faster (no PBKDF2 step any more).
- Removed the `fflate` dependency and the v1 developer switch.
- Release notes now come from this changelog.

## 0.1.31-dev3
- Release notes now come from this file: the section for the version being published becomes the GitHub Release text.

## 0.1.31-dev2
- Dropped the v1 message format completely. v1 messages are no longer read or sent; stored v1 keys are deleted on start.
- Setting a key is faster (no PBKDF2 step any more).
- Removed the `fflate` dependency and the v1 developer switch.

## 0.1.31-dev1
- Start of the 0.1.31 development line.

## 0.1.30 - stable
Everything from the 0.1.30 development builds:
- Settings are now a native Kotlin screen: global key, behaviour, plugin versions, message format and AES preset, Padmé padding, built-in channels, per-chat keys. The gear opens it directly.
- Developer tools (self-test, key-derivation timing, crypto benchmark, engine picker, custom Argon2id size) in their own Kotlin screen. Developer builds only.
- Crypto benchmark moved to Java (`CryptoBench`) and shows Argon2id, HKDF and AES-GCM per engine.
- Faster C Argon2id. The slow JavaScript Argon2id is gone; key derivation is native only.
- Videos play inside the chat (controls hide after 2 s); in-chat audio player.
- The E2EE button hides over the image viewer and the search screen; the keyboard no longer covers the settings fields.
- Removed the experimental GIF dry run.
- GitHub Releases with the installable zip for every build.

## 0.1.29 - stable
- E2EE on/off button in the chat header (left of the search bar).
- Encrypted images and videos: placeholders while decrypting, tap to decrypt large files, a skeleton right away and thumbnail reload.
- Sending several attachments sends one message per file, each with its own size tag, so every image keeps its true size.
- Video play button, audio player, link previews for decrypted messages (opt-in).
- Removed the How to use card (the header button replaces it); file format label shows v2.
- CI: Gradle caching, parallel builds, job timeouts.

## 0.1.28 - stable
- First release split into channels: `main` publishes stable (`latest`), `dev` publishes `-devN` builds (`beta`); developer-only features are compiled out of stable builds.
- Build number shows `-release` or `-dev`.

## 0.1.27
- Plugin description, benchmark skips unavailable engines instead of failing.

## 0.1.26
- Crypto benchmark (Argon2id, HKDF, AES-GCM) in the style of the Aegires app, with a C HKDF.

## 0.1.21 - 0.1.25
- Native C Argon2id through JNI with the Java version as fallback (about 1.4x faster than Java at 64 MiB).
- Argon2id benchmark with an engine picker and custom size options (developer).
- Lock icon: shown next to the time in the message header only; leftover front locks removed.

## 0.1.15 - 0.1.20
- File limit raised to 1 GiB with native streaming for files picked from the phone.
- Lock icon next to the message time, header lock via native chat rows, diagnostics for it.
- Image and video size tags: more size sources, HEIC/AVIF fallback.
- Settings: Plugin versions card (tap the build number 5 times), 1-character password option for testing, build number `YYMMVVVV`.

## 0.1.7 - 0.1.14
- Encrypted attachments: native file encryption (v3 file format, 64 KiB chunks), random `.enc` upload names.
- Received encrypted images and videos are decrypted and shown; image, GIF and video size metadata is probed on send and applied on receive.
- Tap a decrypted video to play it full screen with the native player.
- Native v2 message operations with the vendored Aegires Java `Crypto`, cross-checked against the JavaScript code.

## 0.1.0 - 0.1.6
- First version: end-to-end encrypted text messages (`§<base64>`) compatible with the Aegires Android app, Aegires v2 (Argon2id, per-message key bound to chat and sender, AES-GCM).
- Native Argon2id in Kotlin/Java so key derivation does not freeze the app.
- `\e2ee on|off|status` command per chat (the slash command crashed command search, so it is typed).
- Diagnostics: native Argon2id status, real-size failures.
