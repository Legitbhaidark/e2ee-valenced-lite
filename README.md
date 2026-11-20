# E2EE Lite for Valenced

A small, stable end-to-end encryption plugin for Discord on [Valenced](https://github.com/Valenced), compatible with the
Aegires Android app (Aegires v2: Argon2id, HKDF, AES-GCM). It is a trimmed build of Aegires E2EE.

**Install**
In Valenced, add the repository `https://legitbhaidark.github.io/e2ee-valenced-lite/index.json`, then install **E2EE Lite** from it. (Do not use GitHub's "Download ZIP" of this repository: that is the source code, not a plugin package.)

**What it does**
- Encrypts and decrypts text messages, images, GIFs, videos, audio and files with a shared password.
- An **E2EE** button in the chat header switches encryption on or off for that chat; decrypted messages get a lock icon.
- Videos and audio play in the chat and full screen.

**Settings** (same as the Aegires app's Session menu)
- **Set encryption key**
- **Encryption preset** (AES-128-GCM, AES-192-GCM, AES-256-GCM)

**Layout**
- `plugins/aegires-e2ee/` - the plugin (plugin id `io.github.legitbhaidark.e2ee-lite`): `js/` (hooks, crypto), `src/main/kotlin` and `src/main/java` (native module), `src/main/c` (fast Argon2id).
- `RELEASING.md` - branches, builds and channels. `main` is stable, `dev` builds are `-devN` pre-releases.

Build with `./gradlew` (see `RELEASING.md`). Tests: `node --experimental-strip-types plugins/aegires-e2ee/test/<name>.mjs`.

The Java crypto is copied from the Aegires app (see `plugins/aegires-e2ee/LGPL-NOTICE.md`).
