// The settings screen is drawn by the native half (Kotlin SettingsScreen). This file keeps the real
// values (storage) and does the actions that need JS: it opens the screen with the current values,
// then polls what the user did (a toggle, "Set global key", ...) and applies it.
import { nativeReady, nativeSettingsOpen, nativeSettingsTake, nativeSettingsUpdate } from "./engine/engine.ts";
import { storage, storageNote } from "./storage.ts";
import { notify } from "./notify.ts";
import { MAX_KDF_MIB, MAX_KDF_PASSES, PRESETS, applyDevKdf, clearGlobalKey, getPreset, hasGlobalKey, listChannelConfigs, passwordRule, removeChannelConfig, setChannelEnabled, setChannelPassword, setGlobalPassword, setPreset } from "./state.ts";
import { selectedChannelId } from "./patch.ts";
import { benchCrypto, fmtMs, measureKdf, runSelfTest } from "./selftest.ts";
import { getKdfOverride } from "./crypto/kdf2.ts";
import { diag, diagLines } from "./diag.ts";
import { getSelfId } from "./self.ts";
import { maxPlaintextBytes } from "./crypto/v2.ts";
import { FORCED_CHANNELS } from "./forced.ts";
import { clearLinkPreviews } from "./patches/embeds.ts";
import { BUILD_ID, DEVELOPER, DEV_BUILD, PLUGIN_VERSION } from "./about.ts";

const POLL_MS = 300;

/** The on/off settings the screen may change. "debug" exists in dev builds only. */
export const TOGGLES: string[] = ["encryptByDefault", "attachmentsExp", "linkPreviews", "inlineVideo", "encButton", "showLock", "padme", ...(DEV_BUILD ? ["debug"] : [])];
const DEFAULT_ON = new Set(["inlineVideo", "encButton", "padme"]); // a missing value means on

const toggleValue = (k: string): boolean => (DEFAULT_ON.has(k) ? storage[k] !== false : !!storage[k]);

function currentChannelId(): string {
  try {
    return selectedChannelId();
  } catch {
    return "";
  }
}

/** The saved chats, as the screen shows them. */
const chatList = () => listChannelConfigs().map((c) => ({ id: c.id, hasKey: c.hasKey, enabled: c.enabled === true }));

let closeCb: (() => void) | null = null;

/** The Diagnostics text (developer builds). */
function diagText(): string {
  return (
    "global key: " + (hasGlobalKey() ? "set" : "NOT SET") +
    "\nformat: " + (storage.format === "v1" ? "v1" : "v2 " + getPreset().label) +
    (storageNote() ? "\nSTORAGE: " + storageNote() : "") +
    "\nown user id: " + (getSelfId() ? "known" : "NOT FOUND YET") +
    "\n" + diagLines().join("\n")
  );
}

/** Everything the developer screen shows besides result lines. Developer builds only. */
function devSnapshot(): Record<string, unknown> {
  return {
    diagText: diagText(),
    v1: storage.format === "v1",
    shortPw: !!storage.devShortPw,
    engine: String(storage.devArgonEngine ?? "auto"),
    kdfOn: !!storage.devKdfOn,
    kdfMem: Number(storage.devKdfMemMiB ?? 64),
    kdfPasses: Number(storage.devKdfPasses ?? 3),
    maxMem: MAX_KDF_MIB,
    maxPasses: MAX_KDF_PASSES,
  };
}

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
    chanId: currentChannelId(),
    chats: chatList(),
    ...(DEV_BUILD ? { dev: devSnapshot() } : {}),
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
    } else if (ev?.t === "dev") {
      if (DEV_BUILD) await handleDev(ev); // a release build ignores every developer event
    } else if (ev?.t === "saveChat" && typeof ev.id === "string") {
      await saveChat(ev.id, typeof ev.pw === "string" ? ev.pw : "");
    } else if (ev?.t === "chatEnabled" && typeof ev.id === "string") {
      setChannelEnabled(ev.id, ev.v === true);
      await nativeSettingsUpdate(JSON.stringify({ chats: chatList() }));
    } else if (ev?.t === "removeChat" && typeof ev.id === "string") {
      removeChannelConfig(ev.id);
      await nativeSettingsUpdate(JSON.stringify({ chats: chatList() }));
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

let devBusy = false;

/** Runs one slow developer action with the busy label, then reports. */
async function devRun(what: string, fn: (progress: (f: number) => void) => Promise<void>): Promise<void> {
  if (devBusy) return;
  devBusy = true;
  let last = -1;
  const progress = (f: number) => {
    const pct = Math.round(f * 100);
    if (pct !== last) {
      last = pct;
      void nativeSettingsUpdate(JSON.stringify({ devBusy: what + " " + pct + "%" })).catch(() => {});
    }
  };
  await nativeSettingsUpdate(JSON.stringify({ devBusy: what + "..." }));
  try {
    await fn(progress);
  } catch (e) {
    notify(what + " failed: " + String((e as any)?.message ?? e).split("\n")[0].slice(0, 120));
  }
  devBusy = false;
  await nativeSettingsUpdate(JSON.stringify({ devBusy: "", dev: devSnapshot() }));
}

const update = (o: Record<string, unknown>) => nativeSettingsUpdate(JSON.stringify(o));

/** Developer screen events (developer builds only): {"t":"dev","a":<action>,...}. */
async function handleDev(ev: any): Promise<void> {
  const a = ev.a;
  if (a === "diag") {
    await update({ dev: devSnapshot() });
  } else if (a === "selftest") {
    await devRun("Running", async () => {
      await update({ devLines: [] });
      const r = await runSelfTest();
      await update({ devLines: r.lines });
      notify(r.ok ? "Self-test passed" : "Self-test FAILED");
    });
  } else if (a === "measure") {
    await devRun("Measuring", async (progress) => {
      await update({ devLines: [] });
      const jobs = diag.engineJobs;
      const ms = await measureKdf(progress);
      const how = diag.engineJobs > jobs ? "natively - " + (diag.argon2Engine || "Kotlin") : "FAILED - native: " + (diag.installed["native crypto"] ?? "not started") + (diag.engineLastError ? ", last error: " + diag.engineLastError : "");
      const o = getKdfOverride();
      await update({ devLines: [`Argon2id ${o ? o.mKiB / 1024 + " MiB (custom), " + o.t + " passes" : "64 MiB"} took ${fmtMs(ms)} on this phone, ${how}`] });
    });
  } else if (a === "bench") {
    const mib = Number(ev.mib);
    if (![1, 8, 32, 64].includes(mib)) return;
    await devRun("Benchmarking", async (progress) => {
      await update({ devBench: [] });
      await benchCrypto(mib, ev.js === true, (l) => { void update({ devBench: l }).catch(() => {}); }, progress);
    });
  } else if (a === "set" && (ev.k === "v1" || ev.k === "shortPw")) {
    if (ev.k === "v1") storage.format = ev.v === true ? "v1" : "v2";
    else storage.devShortPw = ev.v === true;
    await update({ format: storage.format === "v1" ? "v1" : "v2", dev: devSnapshot() });
  } else if (a === "engine" && ["auto", "c", "java"].includes(ev.v)) {
    storage.devArgonEngine = ev.v;
    applyDevKdf();
    await update({ dev: devSnapshot() });
  } else if (a === "kdfOn") {
    storage.devKdfOn = ev.v === true;
    applyDevKdf();
    await update({ dev: devSnapshot() });
  } else if (a === "kdfSave") {
    const m = Number(ev.mem), t = Number(ev.passes);
    if (!Number.isInteger(m) || m < 1 || m > MAX_KDF_MIB) return notify("Memory must be a whole number from 1 to " + MAX_KDF_MIB + " MiB");
    if (!Number.isInteger(t) || t < 1 || t > MAX_KDF_PASSES) return notify("Passes must be a whole number from 1 to " + MAX_KDF_PASSES);
    storage.devKdfMemMiB = m;
    storage.devKdfPasses = t;
    applyDevKdf();
    notify("Saved: " + m + " MiB, " + t + " passes" + (storage.devKdfOn ? "" : " (not forced yet)"));
    await update({ dev: devSnapshot() });
  }
}

/** Per-chat key / switch: optional own password for this chat, and switch encryption on for it. */
async function saveChat(id: string, pw: string): Promise<void> {
  if (!/^\d+$/.test(id)) {
    notify("Channel ID must be digits only");
    return;
  }
  try {
    if (pw) {
      let last = -1;
      await nativeSettingsUpdate(JSON.stringify({ chatBusy: true, chatStatus: "Deriving key..." }));
      await setChannelPassword(id, pw, (f) => {
        const pct = Math.round(f * 100);
        if (pct !== last) {
          last = pct;
          void nativeSettingsUpdate(JSON.stringify({ chatStatus: "Deriving key " + pct + "%" })).catch(() => {});
        }
      });
    }
    setChannelEnabled(id, true);
    notify("Chat settings saved");
  } catch (e) {
    notify("Save chat settings failed: " + String((e as any)?.message ?? e).split("\n")[0].slice(0, 120));
  }
  await nativeSettingsUpdate(JSON.stringify({ chatBusy: false, chatStatus: "", chats: chatList() }));
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
