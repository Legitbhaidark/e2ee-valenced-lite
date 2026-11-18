// One-time hint after the plugin is installed or updated: the header button can be switched on and off.
// Shown as a small native toast, once per plugin version. The settings screen explains the button too.
import { PLUGIN_VERSION } from "./about.ts";
import { nativeToast } from "./engine/engine.ts";
import { storage } from "./storage.ts";
import { quiet } from "./notify.ts";

export const BUTTON_HINT =
  "Aegires E2EE: tap the \u{1F512} E2EE button in a chat's header to switch encryption on or off for that chat. You can hide it in the plugin settings.";

/** Returns true when the hint was shown. Call once native is ready. */
export async function announceUpdate(): Promise<boolean> {
  if (storage.seenVersion === PLUGIN_VERSION) return false;
  storage.seenVersion = PLUGIN_VERSION; // set first: a crash after this must not repeat the hint on every start
  if (storage.encButton === false) return false; // the person already turned the button off
  try {
    await nativeToast(BUTTON_HINT);
    return true;
  } catch (e) {
    quiet("update hint failed: " + String((e as any)?.message ?? e).split("\n")[0]);
    return false;
  }
}
