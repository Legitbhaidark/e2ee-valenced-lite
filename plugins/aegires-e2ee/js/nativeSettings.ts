// The settings screen is drawn by the native half (Kotlin SettingsScreen). This file keeps the real
// values (storage) and does the actions that need JS: it opens the screen with the current values,
// then polls what the user did (a toggle, "Set global key", ...) and applies it.
import { nativeReady, nativeSettingsOpen, nativeSettingsTake, nativeSettingsUpdate } from "./engine/engine.ts";
import { storage } from "./storage.ts";
import { diag } from "./diag.ts";
import { notify } from "./notify.ts";
import { PRESETS, clearGlobalKey, getPreset, hasGlobalKey, passwordRule, setGlobalPassword, setPreset } from "./state.ts";
import { maxPlaintextBytes } from "./crypto/v2.ts";
import { FORCED_CHANNELS } from "./forced.ts";
import { clearLinkPreviews } from "./patches/embeds.ts";
import { BUILD_ID, DEVELOPER, DEV_BUILD, PLUGIN_VERSION } from "./about.ts";

const POLL_MS = 300;

/** The on/off settings the screen may change. "debug" exists in dev builds only. */
export const TOGGLES: string[] = ["encryptByDefault", "attachmentsExp", "linkPreviews", "inlineVideo", "encButton", "showLock", "padme", ...(DEV_BUILD ? ["debug"] : [])];
const DEFAULT_ON = new Set(["inlineVideo", "encButton", "padme"]); // a missing value means on

const toggleValue = (k: string): boolean => (DEFAULT_ON.has(k) ? storage[k] !== false : !!storage[k]);

/** Everything the screen shows. */
export function snapshot(): Record<string, unknown> {
  const values: Record<string, boolean> = {};
  for (const k of TOGGLES) values[k] = toggleValue(k);
  return {
    hasKey: hasGlobalKey(),
    rule: passwordRule(),
    values,
    format: storage.format === "v1" ? "v1" : "v2",
    preset: getPreset().index,
    presets: PRESETS.map((x: any) => ({ index: x.index, label: x.label, max: maxPlaintextBytes(x) })),
    forced: Object.keys(FORCED_CHANNELS),
    autoMb: Number(storage.autoDecryptMB ?? 25),
    isDev: DEV_BUILD,
    version: PLUGIN_VERSION,
    build: BUILD_ID,
    developer: DEVELOPER,
    native: String(diag.installed["native crypto"] ?? "not started"),
  };
}

/** Applies one thing the user did on the screen. Never throws. */
export async function handleEvent(ev: any): Promise<void> {
  try {
    if (ev?.t === "set" && typeof ev.k === "string") {
      if (ev.k === "autoDecryptMB") {
        const digits = String(ev.v ?? "").replace(/[^0-9]/g, "").slice(0, 4);
        storage.autoDecryptMB = digits === "" ? 25 : Number(digits);
      } else if (TOGGLES.includes(ev.k)) {
        storage[ev.k] = ev.v === true;
        if (ev.k === "linkPreviews" && ev.v !== true) clearLinkPreviews();
      }
    } else if (ev?.t === "setPreset") {
      const i = Number(ev.i);
      if (PRESETS.some((x: any) => x.index === i)) {
        storage.format = "v2";
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
  }
}

/** Opens the native settings screen. Returns "" when it opened, else a short reason to show the user. */
export async function openNativeSettings(): Promise<string> {
  if (!nativeReady()) return "The native module is not loaded, so the settings screen cannot open.";
  try {
    await nativeSettingsOpen(JSON.stringify(snapshot()));
  } catch (e) {
    return "Could not open the settings screen: " + String((e as any)?.message ?? e).split("\n")[0].slice(0, 100);
  }
  if (!polling) void pump();
  return "";
}
