// Draws the lock to the right of the name, next to the time, in a decrypted message's header
// ("Name  \u{1F512} 11:55 PM") instead of in front of its text.
//
// Discord's chat is native: JS turns each message into a row object (RowManager.generate) and
// sends the rows to the native list as JSON (DCDChatManager.updateRows / insertRows). We catch the
// rows on both routes, so one working route is enough; a row gets the lock in front of its time
// text once (the startsWith check stops a double lock). If neither route works, or rows carry no
// text time, receive.ts falls back to the lock in front of the message text, so the marker never
// disappears silently. The Diagnostics line "header lock" says which case you are in.
import { after, before } from "../patch.ts";
import { rnModule, unscoped } from "../host.ts";
import { storage } from "../storage.ts";
import { LOCK, lockMode } from "../state.ts";
import { diag } from "../diag.ts";
import { wasEncrypted } from "./receive.ts";

let routes = ""; // which routes are installed, for the diagnostics line
let marked = 0;
let shape = ""; // keys of the first encrypted row's message, to learn the row format
const note = (msg: string) => { diag.installed["header lock"] = msg; };
const status = (extra = "") => note([routes || "no route yet", marked ? `marked ${marked} rows` : "", shape, extra].filter(Boolean).join(" | "));

/** Puts the lock in front of the time text of a row of a decrypted message. True if it changed the row. */
function mark(row: any): boolean {
  const msg = row?.message;
  if (!msg || typeof msg !== "object" || !storage.showLock) return false;
  const id = msg.id;
  if ((typeof id !== "string" && typeof id !== "number") || !wasEncrypted(String(id))) return false;
  if (!shape) {
    shape = "row.message keys: " + Object.keys(msg).slice(0, 16).join(",");
    status();
  }
  for (const k of ["timestamp", "timestampText"]) {
    const t = msg[k];
    if (typeof t === "string") {
      if (t.startsWith(LOCK)) return false;
      msg[k] = LOCK + t;
      marked++;
      if (marked === 1) status();
      return true;
    }
  }
  if (lockMode.header) {
    lockMode.header = false; // put the lock back in the text
    status("no text time (timestamp is " + typeof msg.timestamp + ") - lock shown in the text");
  }
  return false;
}

export function installHeaderLock(): () => void {
  const undo: Array<() => void> = [];
  const timers: any[] = [];
  const patched = new Set<any>();
  let stop = () => {};
  let nativePatched = false;
  status("looking for the chat rows");

  const armed = (route: string) => {
    routes = routes ? routes + " + " + route : route;
    lockMode.header = true;
    status();
  };

  // Route 1: RowManager.generate builds the row objects.
  try {
    const { finders } = unscoped().modules;
    const f = finders.filters;
    const filter = f.anyOf(f.withName("RowManager"), f.withDefaultExport(f.withName("RowManager")));
    stop = finders.getModules(filter, (exp: any) => {
      try {
        const cls = typeof exp === "function" ? exp : exp?.default;
        const proto = cls?.prototype;
        if (!proto || typeof proto.generate !== "function" || patched.has(proto)) return;
        patched.add(proto);
        undo.push(after("generate", proto, (row: any) => {
          try { mark(row); } catch {}
          return row;
        }));
        armed("RowManager.generate");
      } catch (e) {
        status("RowManager patch failed: " + String((e as any)?.message ?? e).slice(0, 60));
      }
    });
  } catch (e) {
    status("RowManager lookup failed: " + String((e as any)?.message ?? e).slice(0, 60));
  }

  // Route 2: the JSON rows sent to the native chat list. React Native binds late, so retry for a while.
  const tryNative = (): boolean => {
    if (nativePatched) return true;
    let mgr: any;
    try { mgr = rnModule()?.NativeModules?.DCDChatManager; } catch {}
    if (!mgr) return false;
    for (const name of ["updateRows", "insertRows"]) {
      if (typeof mgr[name] !== "function") continue;
      try {
        undo.push(before(name, mgr, (args: any[]) => {
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
        }));
        nativePatched = true;
      } catch (e) {
        status("DCDChatManager." + name + " patch failed: " + String((e as any)?.message ?? e).slice(0, 60));
      }
    }
    if (nativePatched) armed("DCDChatManager rows");
    return nativePatched;
  };
  if (!tryNative()) {
    let tries = 0;
    const iv = setInterval(() => {
      if (tryNative() || ++tries >= 20) {
        clearInterval(iv);
        if (!nativePatched && !patched.size) status("no route found - lock shown in the text");
      }
    }, 1000);
    timers.push(iv);
  }

  return () => {
    timers.splice(0).forEach((t) => clearInterval(t));
    try { stop(); } catch {}
    undo.splice(0).forEach((u) => { try { u(); } catch {} });
    patched.clear();
    nativePatched = false;
    routes = "";
    marked = 0;
    shape = "";
    lockMode.header = false;
  };
}
