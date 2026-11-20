// Plugin settings + key management.
//
// Only the Aegires v2 format exists (v1 support was dropped in 0.1.31):
//   Argon2id master key (64 MiB, slow), per-message HKDF keys bound to (channel, author),
//   AES-128/192/256-GCM presets.
// The key is derived ONCE when you type the password and only the derived key is stored (hex);
// the password itself is never saved. The derived key is still a secret: anyone who can read this
// plugin's storage can read your chats.
import { DEV_BUILD } from "./about.ts";
import { storage } from "./storage.ts";
import { deriveMasterKey, setArgonEngine, setKdfOverride, type ArgonEngine } from "./crypto/kdf2.ts";
import { fromHex, toHex } from "./crypto/hex.ts";
import { AES128, AES256, PRESETS, presetFromIndex, type Preset } from "./crypto/v2.ts";
import { FORCED_CHANNELS } from "./forced.ts";
import { diag } from "./diag.ts";

interface ChannelConfig {
  keyV2?: string; // v2 channel key; falls back to the global key
  enabled?: boolean; // encrypt outgoing messages here? (falls back to encryptByDefault)
}

export function initStorage(): void {
  storage.keyV2 ??= "";
  storage.devMode = DEV_BUILD; // developer options: always on in dev builds, never in release builds
  storage.devShortPw ??= false; // developer option: allow 1-character passwords
  storage.devKdfOn ??= false; // developer option: custom Argon2id passes / memory (keys no longer match other clients)
  storage.devKdfMemMiB ??= 64;
  storage.devKdfPasses ??= 3;
  storage.devArgonEngine ??= "auto"; // developer option: auto | c | java
  // v1 is gone: drop its leftovers (old keys, format choice, per-chat v1 keys)
  delete storage.globalKeyHex;
  delete storage.format;
  for (const id of Object.keys(storage.channels ?? {})) delete storage.channels[id]?.keyHex;
  storage.preset ??= AES256.index;
  storage.padme ??= true;
  storage.channels ??= {};
  storage.encryptByDefault ??= false;
  storage.showLock ??= true;
  storage.autoDecryptMB ??= 25; // encrypted files above this size (MB) show "Tap to decrypt"; 0 = always ask
  storage.debug ??= false;
  if (!DEV_BUILD) {
    // A release build ignores everything the developer options set (e.g. left over from a dev build).
    storage.devShortPw = false;
    storage.devKdfOn = false;
    storage.devArgonEngine = "auto";
    storage.debug = false;
  }
  applyDevKdf();
}

export const MAX_KDF_MIB = 256; // the native half refuses more
export const MAX_KDF_PASSES = 16;

/** Pushes the developer Argon2id options (custom size, engine) into the key derivation. Off unless developer options are on. */
export function applyDevKdf(): void {
  const on = !!storage.devMode;
  const mem = Math.floor(Number(storage.devKdfMemMiB));
  const t = Math.floor(Number(storage.devKdfPasses));
  const valid = mem >= 1 && mem <= MAX_KDF_MIB && t >= 1 && t <= MAX_KDF_PASSES;
  setKdfOverride(on && storage.devKdfOn && valid ? { t, mKiB: mem * 1024 } : null);
  const e = String(storage.devArgonEngine);
  setArgonEngine(on && (e === "c" || e === "java") ? (e as ArgonEngine) : "auto");
}

/** Shown in front of the time of decrypted messages (or, if that hook fails, in front of the text). */
export const LOCK = "🔒 ";

/** True while the lock is drawn next to the message time (patches/header.ts), so the text stays clean. */
export const lockMode = { header: false, /** true only when the header lock could not be installed: then the lock goes in front of the text */ front: false };

const keyCache = new Map<string, Uint8Array>();

function keyFromHex(hex: string | undefined): Uint8Array | null {
  if (!hex) return null;
  const hit = keyCache.get(hex);
  if (hit) return hit;
  const parsed = fromHex(hex);
  if (parsed && parsed.length === 32) {
    keyCache.set(hex, parsed);
    return parsed;
  }
  return null;
}

function cfg(channelId: string): ChannelConfig | undefined {
  return storage.channels?.[channelId];
}

// ------------------------------------------------------------------ forced (built-in) channels

/** Aegires channels with a built-in key (ForcedKeys.java): always encrypted, v2, AES-128. */
export function isForced(channelId: string | undefined): boolean {
  return !!channelId && Object.prototype.hasOwnProperty.call(FORCED_CHANNELS, channelId);
}

function forcedKey(channelId: string | undefined): Uint8Array | null {
  return channelId && isForced(channelId) ? keyFromHex(FORCED_CHANNELS[channelId].keyHex) : null;
}

// ------------------------------------------------------------------------------- keys

function collect(...ks: Array<Uint8Array | null>): Uint8Array[] {
  const out: Uint8Array[] = [];
  for (const k of ks) if (k && out.indexOf(k) < 0) out.push(k);
  return out;
}

/** v2 master key we ENCRYPT with: built-in (forced) > channel key > global key. */
export function getSendKeyV2(channelId: string): Uint8Array | null {
  return forcedKey(channelId) ?? keyFromHex(cfg(channelId)?.keyV2) ?? keyFromHex(storage.keyV2);
}

/** v2 master keys worth trying when DECRYPTING in this channel, most specific first. */
export function getReceiveKeysV2(channelId: string | undefined): Uint8Array[] {
  return collect(forcedKey(channelId), channelId ? keyFromHex(cfg(channelId)?.keyV2) : null, keyFromHex(storage.keyV2));
}

export function hasGlobalKey(): boolean {
  return !!keyFromHex(storage.keyV2);
}

// ------------------------------------------------------------------------ format + preset

export function getPreset(): Preset {
  return presetFromIndex(Number(storage.preset)) ?? AES256;
}

/** v2 preset for OUTGOING messages. Built-in channels are always AES-128 (like Aegires). */
export function getSendPreset(channelId: string): Preset {
  return isForced(channelId) ? AES128 : getPreset();
}

export function setPreset(index: number): void {
  if (presetFromIndex(index)) storage.preset = index;
}

export { PRESETS };

/** True when a key to encrypt with in this channel exists. */
export function canSend(channelId: string): boolean {
  return !!getSendKeyV2(channelId);
}

// ------------------------------------------------------------------------ on / off

/** Should outgoing messages in this channel be encrypted? Built-in channels: always. */
export function isEncryptionOn(channelId: string): boolean {
  if (isForced(channelId)) return true;
  const c = cfg(channelId);
  if (c && typeof c.enabled === "boolean") return c.enabled;
  return !!storage.encryptByDefault;
}

export function setChannelEnabled(channelId: string, enabled: boolean): void {
  storage.channels[channelId] = { ...(storage.channels[channelId] ?? {}), enabled };
}

// ------------------------------------------------------------------------ password -> keys

export function minPasswordLength(): number {
  return storage.devShortPw ? 1 : 8;
}

/** "8 characters" / "1 character", for messages and hints. */
export function passwordRule(): string {
  const n = minPasswordLength();
  return n + (n === 1 ? " character" : " characters");
}

/** Same limits as the Aegires app (unless the developer option for short passwords is on). */
export function checkPassword(password: string): string | null {
  if (password.length < minPasswordLength()) return "Use at least " + passwordRule();
  if (password.length > 256) return "Password too long (max 256)";
  return null;
}

let deriving = false;

/** Derives the master key (hex) for a password. Argon2id is the slow part (progress 0..1). */
async function derive(password: string, onProgress?: (fraction: number) => void): Promise<string> {
  const problem = checkPassword(password);
  if (problem) throw new Error(problem);
  if (deriving) throw new Error("A key is already being derived - wait for it to finish");
  deriving = true;
  const t0 = Date.now();
  try {
    const key = toHex(await deriveMasterKey(password, { onProgress }));
    diag.lastKdfMs = Date.now() - t0;
    return key;
  } finally {
    deriving = false;
  }
}

export async function setGlobalPassword(password: string, onProgress?: (fraction: number) => void): Promise<void> {
  storage.keyV2 = await derive(password, onProgress);
}

export function clearGlobalKey(): void {
  storage.keyV2 = "";
}

export async function setChannelPassword(channelId: string, password: string, onProgress?: (fraction: number) => void): Promise<void> {
  const keyV2 = await derive(password, onProgress);
  storage.channels[channelId] = { ...(storage.channels[channelId] ?? {}), keyV2 };
}

export function removeChannelConfig(channelId: string): void {
  delete storage.channels[channelId];
}

export function listChannelConfigs(): Array<{ id: string; hasKey: boolean; enabled: boolean | undefined }> {
  const out: Array<{ id: string; hasKey: boolean; enabled: boolean | undefined }> = [];
  for (const id of Object.keys(storage.channels ?? {})) {
    const c = storage.channels[id] as ChannelConfig;
    out.push({ id, hasKey: !!c?.keyV2, enabled: c?.enabled });
  }
  return out;
}
