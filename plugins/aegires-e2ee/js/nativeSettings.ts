// The settings screen is drawn by the native half (Kotlin SettingsScreen). This file keeps the real
// values (storage) and does the actions that need JS: it opens the screen with the current values,
// then polls what the user did (set the key, pick the preset) and applies it.
import { nativeReady, nativeSettingsOpen, nativeSettingsTake, nativeSettingsUpdate } from "./engine/engine.ts";
import { notify } from "./notify.ts";
import { PRESETS, clearGlobalKey, getPreset, hasGlobalKey, setGlobalPassword, setPreset } from "./state.ts";
import { maxPlaintextBytes } from "./crypto/v2.ts";

const POLL_MS = 300;

let closeCb: (() => void) | null = null;

/** Everything the screen shows. */
export function snapshot(): Record<string, unknown> {
  return {
    hasKey: hasGlobalKey(),
    preset: getPreset().index,
    presets: PRESETS.map((x: any) => ({ index: x.index, label: x.label, max: maxPlaintextBytes(x) })),
  };
}

/** Applies one thing the user did on the screen. Never throws. */
export async function handleEvent(ev: any): Promise<void> {
  try {
    if (ev?.t === "setPreset") {
      const i = Number(ev.i);
      if (PRESETS.some((x: any) => x.index === i)) {
        setPreset(i);
      }
    } else if (ev?.t === "setKey" && typeof ev.pw === "string" && ev.pw) {
      let last = -1;
      await nativeSettingsUpdate(JSON.stringify({ busy: true, status: "Deriving key..." }));
      try {
        await setGlobalPassword(ev.pw, (f) => {
          const pct = Math.round(f * 100);
          if (pct !== last) {
            last = pct;
            void nativeSettingsUpdate(JSON.stringify({ status: "Deriving key " + pct + "%" })).catch(() => {});
          }
        });
        notify("Global key set");
      } catch (e) {
        notify("Set global key failed: " + String((e as any)?.message ?? e).split("\n")[0].slice(0, 120));
      }
      await nativeSettingsUpdate(JSON.stringify({ busy: false, status: "", hasKey: hasGlobalKey() }));
    } else if (ev?.t === "clearKey") {
      clearGlobalKey();
      notify("Global key cleared");
      await nativeSettingsUpdate(JSON.stringify({ hasKey: false }));
    }
  } catch (e) {
    notify("Settings: " + String((e as any)?.message ?? e).split("\n")[0].slice(0, 120));
  }
}

let polling = false;

async function pump(): Promise<void> {
  polling = true;
  try {
    for (;;) {
      await new Promise((r) => setTimeout(r, POLL_MS));
      const r = await nativeSettingsTake();
      for (const ev of r.events) await handleEvent(ev);
      if (!r.open && r.events.length === 0) break; // closed and everything handled
    }
  } catch {
    // The native half went away: nothing left to poll.
  } finally {
    polling = false;
    const cb = closeCb;
    closeCb = null;
    try { cb?.(); } catch {}
  }
}

/** Opens the native settings screen. Returns "" when it opened, else a short reason to show the user. */
export async function openNativeSettings(onClose?: () => void): Promise<string> {
  if (!nativeReady()) return "The native module is not loaded, so the settings screen cannot open.";
  closeCb = onClose ?? null;
  try {
    await nativeSettingsOpen(JSON.stringify(snapshot()));
  } catch (e) {
    closeCb = null;
    return "Could not open the settings screen: " + String((e as any)?.message ?? e).split("\n")[0].slice(0, 100);
  }
  if (!polling) void pump();
  return "";
}
