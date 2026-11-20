# Changelog

E2EE Lite for Valenced. Newest first. The release workflow uses the `## <version>` section as the
GitHub Release notes for that version; a version without a section gets its commit list instead.
Stable releases come from `main`, `-devN` pre-releases from `dev`.

## 0.1.1 - stable
- Smaller package: the 32-bit x86 native library is no longer built (no phone uses it). arm64-v8a, armeabi-v7a and x86_64 stay; a device without a matching library uses the Java fallback.

## 0.1.0 - stable
First release of E2EE Lite, a trimmed-down build of Aegires E2EE for Valenced, compatible with the Aegires Android app (Aegires v2: Argon2id, AES-GCM).
- Encrypts and decrypts text, images, GIFs, videos, audio and files with a shared password.
- Settings are the same two items as in the Aegires app: **Set encryption key** and **Encryption preset** (AES-128 / 192 / 256-GCM).
- E2EE on/off button in the chat header, a lock icon on decrypted messages, and a short hint after installing or updating.
- Videos and audio play right in the chat (speed, 10 s jumps, seek bar) and in a full-screen player.
- Runs next to the full Aegires E2EE plugin: separate plugin id, native module and cache folder.
- Removed compared to the full plugin: liquid glass, wallpapers and color themes, mini player and picture-in-picture, link previews, per-chat keys screen, developer tools, crypto benchmark and self-test screens.
