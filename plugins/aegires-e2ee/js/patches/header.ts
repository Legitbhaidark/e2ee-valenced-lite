// Draws the lock next to the time in a decrypted message's header ("Name  \u{1F512} 11:55 PM")
// instead of in front of its text.
//
// Discord's chat is native: the JS RowManager turns each message into a row object for it.
// After RowManager.generate builds a row, a decrypted message's time text gets the lock in front.
// If the hook cannot be installed, or a row has no text time, receive.ts goes back to putting
// the lock in front of the message text, so the marker never disappears silently.
import { after } from "../patch.ts";
import { unscoped } from "../host.ts";
import { storage } from "../storage.ts";
import { LOCK, lockMode } from "../state.ts";
import { diag } from "../diag.ts";
import { wasEncrypted } from "./receive.ts";

const note = (msg: string) => { diag.installed["header lock"] = msg; };

function mark(row: any): void {
  const msg = row?.message;
  if (!msg || typeof msg !== "object" || !storage.showLock) return;
  const id = msg.id;
  if ((typeof id !== "string" && typeof id !== "number") || !wasEncrypted(String(id))) return;
  for (const k of ["timestamp", "timestampText"]) {
    const t = msg[k];
    if (typeof t === "string") {
      if (!t.startsWith(LOCK)) msg[k] = LOCK + t;
      return;
    }
  }
  if (lockMode.header) {
    lockMode.header = false;
    note("rows have no text time (timestamp: " + typeof msg.timestamp + ") - lock shown in the text");
  }
}

export function installHeaderLock(): () => void {
  const undo: Array<() => void> = [];
  const patched = new Set<any>();
  let stop = () => {};
  let timer: any;
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
        lockMode.header = true;
        note("RowManager.generate patched");
      } catch (e) {
        note("patch failed: " + String((e as any)?.message ?? e).slice(0, 80));
      }
    });
  } catch (e) {
    note("failed: " + String((e as any)?.message ?? e).slice(0, 80));
  }
  timer = setTimeout(() => {
    if (!patched.size) note("RowManager not found - lock shown in the text");
  }, 20000);
  note("looking for RowManager");

  return () => {
    clearTimeout(timer);
    try { stop(); } catch {}
    undo.splice(0).forEach((u) => { try { u(); } catch {} });
    patched.clear();
    lockMode.header = false;
  };
}
