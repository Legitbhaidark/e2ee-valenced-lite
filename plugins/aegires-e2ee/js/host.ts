// Handle on Valenced's plugin API. Bound in init()/start(), cleared on stop.
//
// `valenced` is not a global: the loader passes it to plugin hooks. Everything that touches
// Valenced goes through here (or patch.ts / storage.ts) so the rest of the code stays host-neutral.
let bound: any = null;

export function bindHost(api: any): void {
  bound = api;
}

export function unbindHost(): void {
  bound = null;
  reactCache = null;
  rnCache = null;
}

/** The unscoped API: patcher, modules, react, discord, ... */
export function unscoped(): any {
  const u = bound?.unscoped;
  if (!u) throw new Error("Valenced API is not bound yet");
  return u;
}

function lookup(props: string[]): any {
  try {
    const { finders } = unscoped().modules;
    const [exports] = finders.lookupModule(finders.filters.withProps(...props));
    return exports;
  } catch {
    return undefined;
  }
}

let reactCache: any = null;
let rnCache: any = null;

/** React. Binds late in Valenced, so never read it at module scope. */
export function reactModule(): any {
  if (reactCache) return reactCache;
  let r: any;
  try { r = unscoped().react?.React; } catch {}
  if (!r || typeof r.createElement !== "function") r = lookup(["createElement", "useState"]);
  return r && typeof r.createElement === "function" ? (reactCache = r) : undefined;
}

/** React Native. Same rule as React: only read it at render or call time. */
export function rnModule(): any {
  if (rnCache) return rnCache;
  let r: any;
  try { r = unscoped().react?.ReactNative; } catch {}
  if (!r || !r.View) r = lookup(["AppRegistry"]);
  return r && r.View ? (rnCache = r) : undefined;
}
