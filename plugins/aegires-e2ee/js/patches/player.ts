// Tap on a decrypted video or audio file -> our native full-screen player (Kotlin VideoPlayer).
//
// The chat shows a decrypted video or audio file as a picture made by the native half (see
// media.ts), so Discord would open its picture viewer on tap. Two hooks catch the tap first:
//   1. the media viewer's openMediaModal(...) - looks for our picture / media file in its arguments;
//   2. the native chat list's onTap* props (set where its element is created) - looks for the
//      media by attachment id / message id.
// If neither recognises one of our files, the original handler runs unchanged.
import { instead, whenFns } from "../patch.ts";
import { reactModule } from "../host.ts";
import { approveDecrypt, decryptedMedia, mediaForUri, placeholderIdFor, placeholderIdForUri, type PlayableMedia } from "./media.ts";
import { nativePlayMedia, nativeReady } from "../engine/engine.ts";
import { storage } from "../storage.ts";
import { diag } from "../diag.ts";


const note = (msg: string) => { diag.installed["video player"] = msg; };

/** Our playable media (picture or media file uris) anywhere in v (bounded walk), in order. */
function mediaIn(v: any, out: PlayableMedia[] = [], depth = 0, seen = new Set<any>()): PlayableMedia[] {
  if (typeof v === "string") {
    const m = mediaForUri(v);
    if (m && !out.some((x) => x.path === m.path)) out.push(m);
    return out;
  }
  if (!v || typeof v !== "object" || depth > 5 || seen.has(v)) return out;
  seen.add(v);
  const vals = Array.isArray(v) ? v.slice(0, 100) : Object.values(v).slice(0, 60);
  for (const x of vals) mediaIn(x, out, depth + 1, seen);
  return out;
}

/** The id of one of our "tap to decrypt" / "decrypting" placeholders anywhere in v (bounded walk), if any. */
function placeholderIn(v: any, depth = 0, seen = new Set<any>()): string | null {
  if (typeof v === "string") return placeholderIdForUri(v);
  if (!v || typeof v !== "object" || depth > 5 || seen.has(v)) return null;
  seen.add(v);
  const vals = Array.isArray(v) ? v.slice(0, 100) : Object.values(v).slice(0, 60);
  for (const x of vals) {
    const id = placeholderIn(x, depth + 1, seen);
    if (id) return id;
  }
  return null;
}

/** A tap on a placeholder starts the decryption (or does nothing while it runs). True = tap handled. */
function placeholderTapped(id: string | null): boolean {
  if (!id) return false;
  try {
    return approveDecrypt(id);
  } catch (e) {
    note("decrypt tap failed: " + String((e as any)?.message ?? e).slice(0, 80));
    return false;
  }
}

/** The media the viewer opens on: the item at the start index if the args name one, else the only one. */
function pickMedia(args: any[]): PlayableMedia | null {
  const all = mediaIn(args);
  if (!all.length) return null;
  for (const a of args) {
    if (!a || typeof a !== "object") continue;
    const idxKey = Object.keys(a).find((k) => /index/i.test(k) && typeof a[k] === "number");
    const list = Object.values(a).find((x) => Array.isArray(x) && x.length > 1) as any[] | undefined;
    if (idxKey && list) return mediaIn(list[a[idxKey]])[0] ?? null;
  }
  return all.length === 1 ? all[0] : null;
}

/** Strings / numbers in a tap event (attachment ids, message id), the message id, and the attachment index. */
function tapRefs(ne: any): { ids: string[]; msg?: string; index?: number } {
  const ids: string[] = [];
  const walk = (v: any, d: number) => {
    if (v == null || d > 3) return;
    if (typeof v === "string" || typeof v === "number") ids.push(String(v));
    else if (typeof v === "object") for (const x of Object.values(v).slice(0, 40)) walk(x, d + 1);
  };
  walk(ne, 0);
  const idx = Object.keys(ne ?? {}).find((k) => /index/i.test(k) && typeof ne[k] === "number");
  const msg = ne?.messageId ?? ne?.message_id;
  return { ids, msg: msg != null ? String(msg) : undefined, index: idx ? ne[idx] : undefined };
}

/** The placeholder for a chat-list tap event: our picture uri in it, else attachment ids / message id + index. */
function placeholderForTap(ne: any): string | null {
  const direct = placeholderIn(ne);
  if (direct) return direct;
  const r = tapRefs(ne);
  return placeholderIdFor(r.ids, r.msg, r.index);
}

/** The media for a chat-list tap event: our picture / file uri in it, else attachment ids / message id + index. */
function mediaForTap(ne: any): PlayableMedia | null {
  const direct = mediaIn(ne)[0];
  if (direct) return direct;
  const r = tapRefs(ne);
  return decryptedMedia(r.ids, r.msg, r.index);
}

function play(m: PlayableMedia): boolean {
  if (!nativeReady()) { note("native module not ready - Discord's viewer used"); return false; }
  // Videos play in the chat (on their own picture) unless that is switched off; audio always uses the full player.
  nativePlayMedia(m.path, m.title, storage.inlineVideo !== false).then(
    () => note("opened " + m.path.slice(m.path.lastIndexOf("/") + 1)),
    (e) => note("player failed: " + String((e as any)?.message ?? e).slice(0, 80)),
  );
  return true;
}

const CHAT_LIST = /ChatList$/;
const seenTaps = new Set<string>();

function wrapTaps(props: any): any {
  let out = props;
  for (const k of Object.keys(props)) {
    if (!/^onTap/.test(k) || typeof props[k] !== "function" || props[k].__aeg) continue;
    const orig = props[k];
    const w = function (this: any, e: any, ...rest: any[]) {
      const ne = e?.nativeEvent ?? e;
      if (!seenTaps.has(k)) {
        seenTaps.add(k);
        diag.installed["video tap " + k] = "keys: " + Object.keys(ne ?? {}).join(",").slice(0, 90);
      }
      if (/Image|Media|Attachment|Video/i.test(k)) {
        try {
          if (placeholderTapped(placeholderForTap(ne))) return undefined;
          const p = mediaForTap(ne);
          if (p && play(p)) return undefined;
        } catch (err) {
          note("tap failed: " + String((err as any)?.message ?? err).slice(0, 80));
        }
      }
      return orig.call(this, e, ...rest);
    };
    (w as any).__aeg = true;
    if (out === props) out = { ...props };
    out[k] = w;
  }
  return out;
}

export function installVideoPlayer(): () => void {
  const undo: Array<() => void> = [];
  const wrapType = (type: any, props: any) => (typeof type === "string" && CHAT_LIST.test(type) && props ? wrapTaps(props) : props);

  // 1. The picture viewer (loaded by Discord the first time it is opened).
  undo.push(whenFns(["openMediaModal"], (mod) => {
    undo.push(instead("openMediaModal", mod, (args, orig) => {
      try {
        if (placeholderTapped(placeholderIn(args))) return undefined;
        const p = pickMedia(args);
        if (p && play(p)) return undefined;
      } catch (e) {
        note("viewer hook failed: " + String((e as any)?.message ?? e).slice(0, 80));
      }
      return orig(...args);
    }));
  }));

  // 2. Taps reported by the native chat list.
  for (const fn of ["jsx", "jsxs", "jsxDEV"]) {
    undo.push(whenFns([fn], (mod) => {
      try {
        undo.push(instead(fn, mod, (args, orig) => orig(args[0], wrapType(args[0], args[1]), ...args.slice(2))));
      } catch {}
    }));
  }
  try {
    const R = reactModule();
    if (R) undo.push(instead("createElement", R, (args, orig) => orig(args[0], wrapType(args[0], args[1]), ...args.slice(2))));
  } catch {}

  note("waiting for a tap");
  return () => undo.splice(0).forEach((u) => { try { u(); } catch {} });
}
