// Draws the lock to the right of the name, next to the time, in a decrypted message's header
// ("Name  \u{1F512} 11:55 PM") instead of in front of its text.
//
// Discord's chat is native: JS turns each message into a row object and sends the rows to the
// native list as JSON. A row gets the lock in front of its time text once (the startsWith check
// stops a double lock). Routes, any one is enough:
//   A. a row-builder class: found by NAME (RowManager) or by SHAPE (a class with a generate()
//      method whose name or methods mention "row");
//   B. the functions that send the JSON rows to the native list (updateRows / insertRows), found
//      by shape in any module and on NativeModules.
// If no route works, or rows carry no text time, receive.ts falls back to the lock in front of the
// message text, so the marker never disappears silently. Diagnostics shows "header lock" (state),
// "header classes" (row-builder candidates seen) and "header natives" (native chat modules seen),
// so a wrong guess about this Discord build can be corrected from a screenshot.
import { after, before, whenFns } from "../patch.ts";
import { rnModule, unscoped } from "../host.ts";
import { storage } from "../storage.ts";
import { LOCK, lockMode } from "../state.ts";
import { diag } from "../diag.ts";
import { wasEncrypted } from "./receive.ts";

let routes = "";
let marked = 0;
let shape = "";
const strippedFields: string[] = [];
const note = (msg: string) => { diag.installed["header lock"] = msg; };
const status = (extra = "") => note([routes || "no route yet", marked ? `marked ${marked} rows` : "", strippedFields.length ? "front lock removed from: " + strippedFields.join(",") : "", shape, extra].filter(Boolean).join(" | "));

/**
 * Removes a leading lock from every text value of a row (at any depth), except the time itself.
 * Discord's native rows keep message text in nested nodes, so this walks the whole row. The paths
 * where a lock was found are shown in Diagnostics. True if anything changed.
 */
function stripTextLock(node: any, path = "row", depth = 0): boolean {
  if (!node || typeof node !== "object" || depth > 8) return false;
  let changed = false;
  for (const k of Object.keys(node)) {
    if (k === "timestamp" || k === "timestampText") continue;
    const v = node[k];
    if (typeof v === "string") {
      if (v.startsWith(LOCK)) {
        node[k] = v.slice(LOCK.length);
        changed = true;
        const where = (path + "." + k).replace(/\.\d+/g, "[]");
        if (!strippedFields.includes(where) && strippedFields.length < 4) {
          strippedFields.push(where);
          status();
        }
      }
    } else if (v && typeof v === "object") {
      if (stripTextLock(v, path + "." + k, depth + 1)) changed = true;
    }
  }
  return changed;
}

/** Puts the lock in front of the time text of a row of a decrypted message. True if it changed the row. */
function mark(row: any): boolean {
  const msg = row?.message;
  if (!msg || typeof msg !== "object" || !storage.showLock) return false;
  const id = msg.id;
  if ((typeof id !== "string" && typeof id !== "number") || !wasEncrypted(String(id))) return false;
  if (!shape) {
    shape = "row keys: " + Object.keys(row).slice(0, 12).join(",") + " | message keys: " + Object.keys(msg).slice(0, 40).join(",");
    status();
  }
  for (const k of ["timestamp", "timestampText"]) {
    const t = msg[k];
    if (typeof t === "string") {
      // The lock now lives next to the time, so remove any lock left in front of the text. Messages
      // decrypted before this hook was ready (or while it was off) carry one in their cached content.
      const stripped = stripTextLock(row);
      if (t.startsWith(LOCK)) return stripped;
      msg[k] = LOCK + t;
      marked++;
      if (marked === 1 || marked % 20 === 0) status();
      return true;
    }
  }
  // Follow-up messages in a group have no header and so no time: nothing to mark, and that is normal.
  return false;
}

/** Marks the rows inside any JSON-array string argument; returns the (possibly rewritten) args. */
function markRowArgs(args: any[]): any[] {
  for (let i = 0; i < args.length; i++) {
    const a = args[i];
    if (typeof a !== "string" || a.charCodeAt(0) !== 91 /* [ */ || a.length > 4_000_000) continue;
    try {
      const rows = JSON.parse(a);
      if (!Array.isArray(rows)) continue;
      let changed = false;
      for (const r of rows) if (mark(r)) changed = true;
      if (changed) args[i] = JSON.stringify(rows);
    } catch {}
  }
  return args;
}

const protoNames = (c: any): string[] => {
  try { return Object.getOwnPropertyNames(c.prototype).filter((n) => n !== "constructor"); } catch { return []; }
};

export function installHeaderLock(): () => void {
  const undo: Array<() => void> = [];
  const stops: Array<() => void> = [];
  const timers: any[] = [];
  const hooked = new Set<any>();
  const classes: string[] = [];
  let nativeKeys = "";
  status("looking for the chat rows");

  const armed = (route: string) => {
    routes = routes ? routes + " + " + route : route;
    lockMode.header = true;
    status();
  };

  const hookRows = (mod: any, name: string, label: string) => {
    try {
      const fn = mod?.[name];
      if (typeof fn !== "function" || hooked.has(fn)) return;
      hooked.add(fn);
      undo.push(before(name, mod, markRowArgs));
      armed(label);
    } catch (e) {
      status(label + " patch failed: " + String((e as any)?.message ?? e).slice(0, 60));
    }
  };

  const hookGenerate = (cls: any, label: string) => {
    const proto = cls?.prototype;
    if (!proto || typeof proto.generate !== "function" || hooked.has(proto)) return;
    hooked.add(proto);
    undo.push(after("generate", proto, (row: any) => {
      try { mark(row); } catch {}
      return row;
    }));
    armed(label);
  };

  try {
    const { finders } = unscoped().modules;
    const f = finders.filters;

    // A1. By name.
    try {
      const byName = f.anyOf(f.withName("RowManager"), f.withDefaultExport(f.withName("RowManager")));
      stops.push(finders.getModules(byName, (exp: any) => {
        try { hookGenerate(typeof exp === "function" ? exp : exp?.default, "RowManager.generate"); } catch {}
      }));
    } catch (e) {
      status("name lookup failed: " + String((e as any)?.message ?? e).slice(0, 60));
    }

    // A2. By shape: any class with a generate() method. Only ones that look row-related are patched
    // (patching is harmless anyway: mark() ignores everything but our decrypted messages' rows).
    try {
      const withProtoFn = f.createFilterGenerator(
        ([name]: [string], _id: any, exports: any) => {
          const c = typeof exports === "function" ? exports : exports?.default;
          return typeof c === "function" && typeof c.prototype?.[name] === "function";
        },
        ([name]: [string]) => "aegiresProto(" + name + ")",
      );
      stops.push(finders.getModules(withProtoFn("generate"), (exp: any) => {
        try {
          const cls = typeof exp === "function" ? exp : exp?.default;
          const names = protoNames(cls);
          const label = String(cls?.name || "(anonymous)");
          if (classes.length < 8) {
            classes.push(label + "[" + names.slice(0, 6).join(",") + "]");
            diag.installed["header classes"] = classes.join(" ; ");
          }
          if (/row/i.test(label) || names.some((n) => /row/i.test(n))) hookGenerate(cls, label + ".generate");
        } catch {}
      }));
    } catch (e) {
      diag.installed["header classes"] = "shape lookup failed: " + String((e as any)?.message ?? e).slice(0, 60);
    }

    // B1. Modules exposing the functions that send rows to the native list.
    for (const fn of ["updateRows", "insertRows"]) {
      try { stops.push(whenFns([fn], (mod) => hookRows(mod, fn, "module." + fn))); } catch {}
    }
  } catch (e) {
    status("module lookup failed: " + String((e as any)?.message ?? e).slice(0, 60));
  }

  // B2. The native chat module itself. React Native binds late, so retry for a while.
  let tries = 0;
  const tryNative = () => {
    let nm: any;
    try { nm = rnModule()?.NativeModules; } catch {}
    if (nm) {
      try {
        if (!nativeKeys) {
          const keys = Object.keys(nm).filter((k) => /DCD|Chat|Row|Message|List/i.test(k)).slice(0, 10);
          nativeKeys = keys.join(",") || "(none matching)";
          diag.installed["header natives"] = nativeKeys;
        }
        for (const k of Object.keys(nm)) {
          if (!/DCDChatManager/i.test(k)) continue;
          for (const fn of ["updateRows", "insertRows"]) hookRows(nm[k], fn, k + "." + fn);
        }
      } catch {}
    } else if (!nativeKeys) {
      diag.installed["header natives"] = "react native module not available yet";
    }
    if (++tries >= 20) {
      clearInterval(iv);
      if (!routes) { lockMode.front = true; status("no route found - lock shown in the text"); }
    }
  };
  const iv = setInterval(tryNative, 1000);
  timers.push(iv);
  tryNative();

  return () => {
    timers.splice(0).forEach((t) => clearInterval(t));
    stops.splice(0).forEach((s) => { try { s(); } catch {} });
    undo.splice(0).forEach((u) => { try { u(); } catch {} });
    hooked.clear();
    routes = "";
    marked = 0;
    shape = "";
    strippedFields.length = 0;
    lockMode.header = false;
    lockMode.front = false;
  };
}
