# Changelog

E2EE Lite for Valenced. Newest first. The release workflow uses the `## <version>` section as the
GitHub Release notes for that version; a version without a section gets its commit list instead.
Stable releases come from `main`, `-devN` pre-releases from `dev`.

## 0.1.2 - stable
- New settings page that looks like Discord's own settings (built from Discord's components): **Password** with *Set key* and *Clear key* and a "Key set." / "No key set." line, and a **Profile** list (AES-128 / 192 / 256-GCM). The on/off switch is not in the settings: the E2EE button in the chat header does that.
- The gear button now opens this page directly (the separate dialog is gone, so no more settings inside settings).
- If a Discord update renames one of its components, the page falls back to plain controls with the same two settings instead of crashing.

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
