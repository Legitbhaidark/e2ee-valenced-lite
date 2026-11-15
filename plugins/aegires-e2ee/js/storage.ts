// Sync facade over Valenced's async JSON storage (api.jsonStorage).
//
// The plugin reads and writes `storage.x` synchronously everywhere. We load the file once in
// init(), serve reads from jsonStorage.cache, and write the whole document back (debounced)
// after any change, however deep. This file has no Valenced imports: attachStorage() is handed
// the jsonStorage object, so it can be tested on plain Node.
interface JsonStorageLike {
  cache?: any;
  get(): Promise<any>;
  set(value: any, replace?: boolean): Promise<void>;
}

const WRITE_DELAY_MS = 300;

let store: JsonStorageLike | null = null;
const fallback: Record<string, any> = {}; // used before attach, and when the file is unreadable
let writable = true;
let note = "";
let timer: any = null;
let saving: Promise<void> = Promise.resolve();

const raws = new WeakMap<object, object>(); // proxy -> raw object
const proxies = new WeakMap<object, any>(); // raw object -> proxy

const isObj = (v: unknown): v is object => typeof v === "object" && v !== null;
const unwrap = (v: any): any => (isObj(v) ? raws.get(v) ?? v : v);
const cur = (): any => (store?.cache && isObj(store.cache) ? store.cache : fallback);

function doSave(): Promise<void> {
  if (!store || !writable || !isObj(store.cache)) return Promise.resolve();
  return store.set(store.cache, true).catch((e) => {
    console.log("[aegires-e2ee] could not save storage", e);
  });
}

function schedule(): void {
  if (!store || !writable || timer) return;
  timer = setTimeout(() => {
    timer = null;
    saving = saving.then(doSave);
  }, WRITE_DELAY_MS);
}

/** Writes pending changes now. */
export function flush(): Promise<void> {
  if (timer) {
    clearTimeout(timer);
    timer = null;
  }
  saving = saving.then(doSave);
  return saving;
}

function deep(raw: object): any {
  const hit = proxies.get(raw);
  if (hit) return hit;
  const p = new Proxy(raw, {
    get(t, k, r) {
      const v = Reflect.get(t, k, r);
      return isObj(v) ? deep(v) : v;
    },
    set(t, k, v) {
      const ok = Reflect.set(t, k, unwrap(v));
      schedule();
      return ok;
    },
    deleteProperty(t, k) {
      const ok = Reflect.deleteProperty(t, k);
      schedule();
      return ok;
    },
  });
  proxies.set(raw, p);
  raws.set(p, raw);
  return p;
}

/** Drop-in for Vendetta's `storage`: plain property reads and writes, saved in the background. */
export const storage: any = new Proxy({} as any, {
  get: (_t, k) => {
    const v = cur()[k];
    return isObj(v) ? deep(v) : v;
  },
  set: (_t, k, v) => {
    cur()[k] = unwrap(v);
    schedule();
    return true;
  },
  deleteProperty: (_t, k) => {
    delete cur()[k];
    schedule();
    return true;
  },
  has: (_t, k) => k in cur(),
  ownKeys: () => Reflect.ownKeys(cur()),
  getOwnPropertyDescriptor: (_t, k) => {
    const d = Reflect.getOwnPropertyDescriptor(cur(), k);
    if (d) d.configurable = true; // the proxy target is a bare object, so it must report configurable
    return d;
  },
});

/**
 * Loads the document. If the file exists but cannot be read, saving stays OFF for this session, so
 * saved keys are never overwritten with defaults because of one failed read.
 */
export async function attachStorage(js: JsonStorageLike): Promise<void> {
  store = js;
  writable = true;
  note = "";
  try {
    await js.get();
  } catch (e) {
    console.log("[aegires-e2ee] could not read storage", e);
  }
  if (!isObj(js.cache)) {
    writable = false;
    note = "storage file unreadable: changes are NOT saved this session";
    js.cache = fallback;
  }
}

/** Empty when storage is fine; otherwise a one-line problem for Diagnostics. */
export function storageNote(): string {
  return note;
}

export async function detachStorage(): Promise<void> {
  await flush();
  store = null;
}
