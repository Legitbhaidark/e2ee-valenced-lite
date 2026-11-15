// Aegires E2EE for Valenced (port of the Kettu plugin). Phase 1: text messages.
//
// Lifecycle: init() loads storage, start() installs the hooks, and everything installed is undone
// through api.cleanup() when the plugin is stopped. Attachments, the WebView crypto engine and the
// video player are not ported yet (see features.ts); uploads are blocked in encrypted chats.
import { quiet } from "./notify.ts";
import { initStorage } from "./state.ts";
import { installSendPatch } from "./patches/send.ts";
import { installReceivePatch } from "./patches/receive.ts";
import { hasSecureRandom } from "./crypto/random.ts";
import Settings from "./ui/Settings.tsx";
import { diag } from "./diag.ts";
import { envSummary } from "./patch.ts";
import { bindHost, unbindHost } from "./host.ts";
import { attachStorage, detachStorage, storageNote } from "./storage.ts";
import { startNative, stopNative } from "./engine/engine.ts";
import { installVideoPlayer } from "./patches/player.ts";
import { installHeaderLock } from "./patches/header.ts";

let undo: Array<() => void> = [];
let timers: any[] = [];

// Each installer is isolated: if one fails (Discord changed something, or Discord has not
// finished loading its modules yet), the other still works. Failed installers are retried
// a few times; the reason is shown in the settings Diagnostics card.
function safely(name: string, install: () => () => void, retry = 3): void {
  try {
    undo.push(install());
    diag.installed[name] = "ok";
  } catch (e) {
    const msg = String((e as any)?.message ?? e).slice(0, 80);
    diag.installed[name] = "FAILED: " + msg + (retry > 0 ? " (retrying)" : "");
    console.log(`[aegires-e2ee] ${name} failed to install`, e);
    if (retry > 0) timers.push(setTimeout(() => safely(name, install, retry - 1), 4000));
    else quiet(`${name} failed: ${msg}`);
  }
}

// Remove everything this instance installed.
function unloadAll(): void {
  timers.forEach((t) => clearTimeout(t));
  timers = [];
  undo.splice(0).reverse().forEach((u) => {
    try { u(); } catch (e) { console.log("[aegires-e2ee] unpatch error", e); }
  });
}

export default plugin({
  async init(api: any) {
    bindHost(api);
    await attachStorage(api.jsonStorage);
    initStorage();
  },

  start(api: any) {
    bindHost(api);
    diag.installed["env"] = envSummary();
    diag.installed["storage"] = storageNote() || "ok";
    if (!hasSecureRandom()) quiet("no secure random source - encrypting is impossible. Run the self-test in settings.");
    safely("send patch", installSendPatch);
    safely("receive patch", installReceivePatch);
    safely("video player", installVideoPlayer);
    safely("header lock", installHeaderLock);
    // Faster Argon2id from the native half, if it is installed and passes its self-check.
    void startNative();
    api.cleanup(unloadAll, stopNative, async () => {
      await detachStorage();
      unbindHost();
    });
  },

  SettingsComponent: Settings,
});
