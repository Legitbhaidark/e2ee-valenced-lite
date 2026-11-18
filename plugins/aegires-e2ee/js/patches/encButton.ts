// The "E2EE" on/off button in the channel header, left of Discord's search icon.
//
// The button itself is drawn by the native half (Kotlin EncryptButton: a small overlay on the window
// that follows the search icon). This file only tells it what to show for the chat that is open and
// reacts to presses:
//   on      encryption is on here (green)
//   off     encryption is off here (grey outline)
//   locked  a chat with a built-in key: always on, a press only explains that
//   hide    no chat open, no key for this chat, the native half is missing, or "E2EE button" is switched off in settings
// A press turns encryption on/off for THIS chat only, the same switch as "/e2ee on|off".
import { nativeEncButton, nativeEncButtonStatus, nativeEncButtonTaps, nativeReady } from "../engine/engine.ts";
import { selectedChannelId } from "../patch.ts";
import { storage } from "../storage.ts";
import { diag } from "../diag.ts";
import { notify } from "../notify.ts";
import { canSend, isEncryptionOn, isForced, setChannelEnabled } from "../state.ts";

const POLL_MS = 300; // presses and the open chat are checked this often
const STATUS_MS = 2000; // the "why is it hidden" line for Diagnostics is asked for this often, not on every poll
const REASSERT_MS = 1500; // the state is re-sent now and then (the overlay is dropped when the activity is recreated)

type BtnState = "on" | "off" | "locked" | "hide";

export function wantedState(channelId: string): BtnState {
  if (storage.encButton === false || !channelId) return "hide";
  if (isForced(channelId)) return "locked";
  if (!canSend(channelId)) return "hide"; // no key for this chat: nothing to switch
  return isEncryptionOn(channelId) ? "on" : "off";
}

/** What a press does in this chat. Returns a message to show when it cannot do what was asked, else "". */
export function pressed(channelId: string): string {
  if (!channelId) return "";
  if (isForced(channelId)) return "This chat has a built-in key and is always encrypted.";
  if (isEncryptionOn(channelId)) {
    setChannelEnabled(channelId, false);
    return "";
  }
  if (!canSend(channelId)) return "No key for this chat yet - set the password in the plugin settings first. Encryption stays off.";
  setChannelEnabled(channelId, true);
  return "";
}

export function installEncButton(): () => void {
  let last: BtnState | "" = "";
  let lastSentAt = 0;
  let statusAt = 0;
  let statusText = "";
  let busy = false;
  let stopped = false;

  const tick = async () => {
    if (busy || stopped) return;
    busy = true;
    try {
      if (!nativeReady()) {
        diag.installed["encryption button"] = "waiting for the native half";
        return;
      }
      let channelId = selectedChannelId();
      if (storage.encButton !== false) {
        const taps = await nativeEncButtonTaps();
        if (taps > 0) {
          // An even number of presses cancels out; each press is one switch.
          const msg = taps % 2 === 1 || isForced(channelId) ? pressed(channelId) : "";
          if (msg) notify(msg);
          channelId = selectedChannelId();
          last = ""; // the native button flipped itself on the press: send the real state back even if unchanged
        }
      }
      const want = wantedState(channelId);
      const now = Date.now();
      if (want !== last || (want !== "hide" && now - lastSentAt > REASSERT_MS)) {
        await nativeEncButton(want);
        last = want;
        lastSentAt = now;
      }
      if (want === "hide") statusText = "";
      else if (now - statusAt >= STATUS_MS) {
        statusText = await nativeEncButtonStatus().catch(() => "");
        statusAt = now;
      }
      const why = statusText;
      diag.installed["encryption button"] = `${want}${channelId ? "" : " (no chat open)"}${why ? " - " + why : ""}`;
    } catch (e) {
      diag.installed["encryption button"] = "native call failed: " + String((e as any)?.message ?? e).split("\n")[0].slice(0, 60);
    } finally {
      busy = false;
    }
  };

  diag.installed["encryption button"] = "starting";
  const timer = setInterval(() => void tick(), POLL_MS);
  void tick();
  return () => {
    stopped = true;
    clearInterval(timer);
    void nativeEncButton("hide").catch(() => {});
  };
}
