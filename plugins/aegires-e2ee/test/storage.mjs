// Tests js/storage.ts (sync facade over Valenced's async JSON storage) against a fake jsonStorage.
// Run from the plugin folder: node test/storage.mjs   (Node 22.18+ runs the .ts file directly)
import assert from "node:assert/strict";
import { attachStorage, detachStorage, flush, storage, storageNote } from "../js/storage.ts";

function fakeStore(file) {
  const s = {
    cache: undefined,
    file,
    writes: 0,
    async get() {
      if (s.file === undefined) {
        s.cache = {};
        s.file = "{}";
        return s.cache;
      }
      if (!s.file) return undefined; // empty file: the real jsonStorage returns nothing and leaves cache unset
      s.cache = JSON.parse(s.file);
      return s.cache;
    },
    async set(value, replace) {
      assert.equal(replace, true);
      s.cache = value;
      s.file = JSON.stringify(value);
      s.writes++;
    },
  };
  return s;
}
const ok = (m) => console.log("  ok  " + m);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// 1. fresh file: defaults, nested writes, delete, key listing
{
  const fs = fakeStore(undefined);
  await attachStorage(fs);
  storage.format ??= "v2";
  storage.channels ??= {};
  storage.channels["111"] = { enabled: true };
  storage.channels["111"].keyV2 = "abcd";
  const prev = storage.channels["222"] ?? {};
  storage.channels["222"] = { ...prev, enabled: false };
  assert.deepEqual(Object.keys(storage.channels), ["111", "222"]);
  assert.ok("format" in storage);
  assert.deepEqual(Object.keys(storage).sort(), ["channels", "format"]);
  await flush();
  assert.deepEqual(JSON.parse(fs.file), { format: "v2", channels: { 111: { enabled: true, keyV2: "abcd" }, 222: { enabled: false } } });
  delete storage.channels["111"];
  await flush();
  assert.deepEqual(Object.keys(JSON.parse(fs.file).channels), ["222"]);
  ok("defaults, nested set, spread, delete, key listing, saved as plain JSON");
  await detachStorage();
}

// 2. existing file is read, not overwritten
{
  const fs = fakeStore(JSON.stringify({ keyV2: "secret", channels: { 5: { enabled: true } }, showLock: false }));
  await attachStorage(fs);
  assert.equal(storage.keyV2, "secret");
  assert.equal(storage.showLock, false);
  assert.equal(storage.channels["5"].enabled, true);
  assert.equal(storageNote(), "");
  storage.showLock ??= true; // already set: no change, so no write is needed
  assert.equal(storage.showLock, false);
  ok("existing values are kept and ??= does not clobber them");
  await detachStorage();
}

// 3. debounce: many changes, one write
{
  const fs = fakeStore(JSON.stringify({}));
  await attachStorage(fs);
  for (let i = 0; i < 20; i++) storage["k" + i] = i;
  storage.nested = { a: { b: 1 } };
  storage.nested.a.b = 2;
  assert.equal(fs.writes, 0);
  await sleep(450);
  assert.equal(fs.writes, 1);
  assert.equal(JSON.parse(fs.file).nested.a.b, 2);
  ok("20+ changes in a burst are written once, deep changes included");
  await detachStorage();
}

// 4. unreadable file: never overwrite it with defaults
{
  const fs = fakeStore("");
  await attachStorage(fs);
  assert.ok(storageNote().length > 0);
  storage.keyV2 = "new";
  assert.equal(storage.keyV2, "new"); // still works in memory
  await flush();
  await sleep(450);
  assert.equal(fs.writes, 0);
  assert.equal(fs.file, "");
  ok("unreadable file: works in memory, saving stays off, file untouched, Diagnostics note set");
  await detachStorage();
}

// 5. flush on detach saves pending changes
{
  const fs = fakeStore(JSON.stringify({}));
  await attachStorage(fs);
  storage.x = 1;
  await detachStorage();
  assert.equal(JSON.parse(fs.file).x, 1);
  ok("detach flushes pending changes");
}

console.log("\nstorage tests passed.");
