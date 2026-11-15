// Module finding and patching on top of Valenced's own APIs (unscoped.modules / .patcher / .discord).
//
// Valenced's patcher takes (object, name, hook), the reverse of Vendetta's (name, object, hook), so
// before() and instead() below keep the old argument order for the callers.
//
// Never wrap Metro module factories ourselves: Valenced warns that one failed factory poisons the
// whole session. getModules() already waits for modules that are not loaded yet.
import { diag } from "./diag.ts";
import { unscoped } from "./host.ts";

export function noteFound(label: string, found: any): void {
  diag.installed["find " + label] = found ? "found" : "NOT FOUND";
}

/**
 * Calls cb with every module export that has all the named functions: right away for loaded
 * modules, later for ones Discord loads afterwards. Several modules can re-export the same
 * function, so cb can run more than once. Returns an unsubscribe.
 */
export function whenFns(names: string[], cb: (mod: any) => void): () => void {
  const { finders } = unscoped().modules;
  return finders.getModules(finders.filters.withProps(...names), (mod: any) => {
    try {
      if (mod && names.every((n) => typeof mod[n] === "function")) cb(mod);
    } catch (e) {
      console.log("[aegires-e2ee] module callback failed", e);
    }
  });
}

/** Replaces obj[name]; cb gets (args, originalFunction). Returns an unpatch. */
export function instead(name: string, obj: any, cb: (args: any[], orig: (...a: any[]) => any) => any): () => void {
  return unscoped().patcher.instead(obj, name, (args: any[], orig: (...a: any[]) => any) => cb(args, orig));
}

/** Runs cb(result) after obj[name] and returns what cb returns (or the result if it returns nothing). */
export function after(name: string, obj: any, cb: (result: any) => any): () => void {
  return unscoped().patcher.after(obj, name, (result: any) => {
    const r = cb(result);
    return r === undefined ? result : r;
  });
}

/** Runs cb(args) before obj[name]; cb may return a new args array. Returns an unpatch. */
export function before(name: string, obj: any, cb: (args: any[]) => any[] | void): () => void {
  return unscoped().patcher.before(obj, name, (args: any[]) => {
    const r = cb(args);
    return Array.isArray(r) ? r : args;
  });
}

/** A Flux store by name (UserStore, SelectedChannelStore, ...); undefined if not found yet. */
export function store(name: string): any {
  try {
    return unscoped().discord.flux.Stores[name];
  } catch {
    return undefined;
  }
}

export function selectedChannelId(): string {
  try {
    const id = store("SelectedChannelStore")?.getChannelId?.();
    if (id) return String(id);
  } catch {}
  return "";
}

/** One-line description of the runtime, for the Diagnostics card. */
export function envSummary(): string {
  try {
    const u = unscoped();
    return `valenced api[${Object.keys(u).join(",")}] patcher[${Object.keys(u.patcher ?? {}).join(",")}]`;
  } catch (e) {
    return "valenced api not bound: " + String((e as any)?.message ?? e);
  }
}
