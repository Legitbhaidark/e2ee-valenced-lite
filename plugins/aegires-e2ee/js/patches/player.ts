// Tap on a decrypted video -> our native full-screen player (Kotlin VideoPlayer).
//
// The chat shows a decrypted video as a first-frame picture (see media.ts), so Discord would open
// its picture viewer on tap. Two hooks catch the tap before that:
//   1. the media viewer's openMediaModal(...) - looks for our video file in its arguments;
//   2. the native chat list's onTap* props (set where its element is created) - looks for the
//      video by attachment id / message id.
// If neither recognises a video, the original handler runs unchanged.
import { instead, whenFns } from "../patch.ts";
import { reactModule } from "../host.ts";
import { decryptedVideoPath } from "./media.ts";
import { nativePlayVideo, nativeReady } from "../engine/engine.ts";
import { diag } from "../diag.ts";


const VIDEO = /\.(mp4|m4v|mov|webm|mkv|3gp)$/i;
const note = (msg: string) => { diag.installed["video player"] = msg; };

const isOurVideo = (s: unknown): s is string =>
  typeof s === "string" && s.startsWith("file://") && s.includes("/aegires/") && VIDEO.test(s.split("?")[0]);

const toPath = (u: string) => {
  const raw = u.split("?")[0].replace(/^file:\/\//, "");
  try { return decodeURIComponent(raw); } catch { return raw; }
};

/** Our video file uris anywhere in v (bounded walk), in order. */
function videosIn(v: any, out: string[] = [], depth = 0, seen = new Set<any>()): string[] {
  if (isOurVideo(v)) {
    const p = toPath(v);
    if (!out.includes(p)) out.push(p);
    return out;
  }
  if (!v || typeof v !== "object" || depth > 5 || seen.has(v)) return out;
  seen.add(v);
  const vals = Array.isArray(v) ? v.slice(0, 100) : Object.values(v).slice(0, 60);
  for (const x of vals) videosIn(x, out, depth + 1, seen);
  return out;
}

/** The video the viewer opens on: the item at the start index if the args name one, else the only video. */
function pickVideo(args: any[]): string | null {
  const all = videosIn(args);
  if (!all.length) return null;
  for (const a of args) {
    if (!a || typeof a !== "object") continue;
    const idxKey = Object.keys(a).find((k) => /index/i.test(k) && typeof a[k] === "number");
    const list = Object.values(a).find((x) => Array.isArray(x) && x.length > 1) as any[] | undefined;
    if (idxKey && list) return videosIn(list[a[idxKey]])[0] ?? null;
  }
  return all.length === 1 ? all[0] : null;
}

/** The video for a chat-list tap event: our file uri in it, else attachment ids / message id + index. */
function videoForTap(ne: any): string | null {
  const direct = videosIn(ne)[0];
  if (direct) return direct;
  const ids: string[] = [];
  const walk = (v: any, d: number) => {
    if (v == null || d > 3) return;
    if (typeof v === "string" || typeof v === "number") ids.push(String(v));
    else if (typeof v === "object") for (const x of Object.values(v).slice(0, 40)) walk(x, d + 1);
  };
  walk(ne, 0);
  const idx = Object.keys(ne ?? {}).find((k) => /index/i.test(k) && typeof ne[k] === "number");
  const msg = ne?.messageId ?? ne?.message_id;
  return decryptedVideoPath(ids, msg != null ? String(msg) : undefined, idx ? ne[idx] : undefined);
}

function play(path: string): boolean {
  if (!nativeReady()) { note("native module not ready - Discord's viewer used"); return false; }
  nativePlayVideo(path).then(
    () => note("opened " + path.slice(path.lastIndexOf("/") + 1)),
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
          const p = videoForTap(ne);
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
        const p = pickVideo(args);
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
