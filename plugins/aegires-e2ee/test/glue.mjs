// Glue test: the plugin's hooks against a FAKE Valenced API (finders, patcher, flux, stores).
// It checks our side of the contract (what we call, what we return), not Valenced itself.
//
// Run from the plugin folder:  node test/glue.mjs     (Node 22.18+ runs the .ts files directly)
// When @noble/* are installed the real crypto runs too, including a full
// send -> receive round trip. When they are missing the crypto packages are stubbed and the
// round trip is skipped.
import assert from "node:assert/strict";
import { register } from "node:module";

let realCrypto = true;
try {
  import.meta.resolve("@noble/hashes/sha256");
} catch {
  realCrypto = false;
  const hooks = `
    const NAMES = ["blake2b","ghash","hkdf","pbkdf2Async","sha1","sha256"];
    export async function resolve(spec, ctx, next) {
      if (/^@noble\\//.test(spec)) return { url: "stub:" + spec, shortCircuit: true };
      return next(spec, ctx);
    }
    export async function load(url, ctx, next) {
      if (url.startsWith("stub:")) {
        const src = NAMES.map((n) => "export const " + n + " = () => { throw new Error('crypto stub: " + n + "'); };").join("\\n");
        return { format: "module", source: src, shortCircuit: true };
      }
      return next(url, ctx);
    }`;
  register("data:text/javascript," + encodeURIComponent(hooks), import.meta.url);
}

const { bindHost, unbindHost } = await import("../js/host.ts");
const { attachStorage, detachStorage, storage } = await import("../js/storage.ts");
const { initStorage, setChannelEnabled } = await import("../js/state.ts");
const { installSendPatch } = await import("../js/patches/send.ts");
const { installReceivePatch } = await import("../js/patches/receive.ts");
const { getSelfId } = await import("../js/self.ts");
const { diag } = await import("../js/diag.ts");

const ok = (m) => console.log("  ok  " + m);
const ME = "999999999999999999";
const CH = "123456789012345678";

// ---------------------------------------------------------------- fake Valenced
function makeApi() {
  const registry = []; // module exports objects Discord "has loaded"
  const waiters = []; // [filter, cb] for modules loaded later
  const fluxHooks = [];
  const api = {
    registry,
    fluxHooks,
    addModule(m) {
      registry.push(m);
      for (const [f, cb] of waiters) if (f.props.every((p) => p in m)) cb(m);
    },
    unscoped: {
      modules: {
        finders: {
          filters: { withProps: (...props) => ({ props }) },
          getModules(filter, cb) {
            for (const m of registry) if (filter.props.every((p) => p in m)) cb(m);
            const w = [filter, cb];
            waiters.push(w);
            return () => waiters.splice(waiters.indexOf(w), 1);
          },
          lookupModule: () => [undefined, undefined],
        },
      },
      patcher: {
        // Valenced order: (object, name, hook). instead hooks get (args, original).
        instead(obj, name, hook) {
          const orig = obj[name];
          obj[name] = function (...args) {
            return hook(args, (...a) => orig.apply(this, a));
          };
          const mine = obj[name];
          return () => {
            if (obj[name] === mine) obj[name] = orig;
          };
        },
        before(obj, name, hook) {
          const orig = obj[name];
          obj[name] = function (...args) {
            return orig.apply(this, hook(args));
          };
          const mine = obj[name];
          return () => {
            if (obj[name] === mine) obj[name] = orig;
          };
        },
      },
      discord: {
        flux: {
          // Valenced: a hook that returns nothing blocks the event.
          onAnyFluxEventDispatched(fn) {
            fluxHooks.push(fn);
            return () => fluxHooks.splice(fluxHooks.indexOf(fn), 1);
          },
          Stores: {
            UserStore: { getCurrentUser: () => ({ id: ME }) },
            SelectedChannelStore: { getChannelId: () => CH },
          },
        },
      },
      react: {},
    },
  };
  return api;
}

/** Runs an event through the fake dispatcher: null means blocked. */
function dispatch(api, event) {
  let e = event;
  for (const h of api.fluxHooks) {
    e = h(e);
    if (!e) return null;
  }
  return e;
}

function makeMessageActions(sent) {
  return {
    receiveMessage() {},
    sendMessage(channelId, message, wait, options) {
      sent.push({ channelId, content: message.content, options });
      return Promise.resolve("sent");
    },
    editMessage(channelId, messageId, message) {
      sent.push({ channelId, messageId, content: message.content, edit: true });
      return Promise.resolve("edited");
    },
  };
}

function freshStore() {
  const s = {
    cache: undefined,
    async get() { s.cache = {}; return s.cache; },
    async set(v) { s.cache = v; },
  };
  return s;
}

// ---------------------------------------------------------------- tests
const api = makeApi();
bindHost(api);
await attachStorage(freshStore());
initStorage();

// 1. module already loaded, encryption off: plaintext passes through untouched
{
  const sent = [];
  const MA = makeMessageActions(sent);
  api.addModule(MA);
  const undoSend = installSendPatch();
  assert.equal(diag.installed["find sendMessage module"], "found");
  await MA.sendMessage(CH, { content: "hello" }, true, {});
  assert.deepEqual(sent.map((s) => s.content), ["hello"]);
  assert.equal(diag.sendCalls, 1);
  ok("loaded module is patched; encryption off sends plaintext unchanged");

  // 2. encryption on but no key: BLOCKED, nothing reaches the real sendMessage
  storage.encryptByDefault = true;
  const r = await MA.sendMessage(CH, { content: "secret" }, true, {});
  assert.equal(r, undefined);
  assert.equal(sent.length, 1);
  assert.match(diag.lastSend, /BLOCKED/);
  ok("encryption on with no key: message is blocked, never sent as plaintext");

  // 3. attachments in an encrypted chat: BLOCKED (not ported yet)
  const r2 = await MA.sendMessage(CH, { content: "pic" }, true, { attachmentsToUpload: [{ uri: "file:///x.png" }] });
  assert.equal(r2, undefined);
  assert.equal(sent.length, 1);
  assert.match(diag.lastSend, /attachment/);
  ok("attachments in an encrypted chat are blocked, not sent as plaintext");

  // 4. attachments with encryption off for that chat: allowed
  setChannelEnabled(CH, false);
  await MA.sendMessage(CH, { content: "pic" }, true, { attachmentsToUpload: [{ uri: "file:///x.png" }] });
  assert.equal(sent.length, 2);
  ok("chat switched off: attachments go through");
  setChannelEnabled(CH, true);

  // 5. unload restores the original functions
  undoSend();
  await MA.sendMessage(CH, { content: "after unload" }, true, {});
  assert.equal(sent.length, 3);
  assert.equal(sent[2].content, "after unload");
  ok("unload removes the patch");
}

// 6. module loaded AFTER the patch is installed, and two modules re-exporting the same functions
{
  const sent = [];
  const base = makeMessageActions(sent);
  const reexport = { sendMessage: base.sendMessage, receiveMessage: base.receiveMessage, editMessage: base.editMessage };
  const apiLate = makeApi();
  bindHost(apiLate);
  const undoSend = installSendPatch();
  assert.equal(diag.installed["find sendMessage module"], "NOT FOUND");
  apiLate.addModule(base);
  apiLate.addModule(reexport);
  assert.equal(diag.installed["find sendMessage module"], "found");
  // encryptByDefault is still on and there is still no key: both routes must block
  assert.equal(await base.sendMessage(CH, { content: "a" }, true, {}), undefined);
  assert.equal(await reexport.sendMessage(CH, { content: "b" }, true, {}), undefined);
  assert.equal(sent.length, 0);
  undoSend();
  ok("late-loaded module is patched; a re-exporting module is patched too (no bypass)");
  bindHost(api);
}

// 7. receive hook: returns the event on every normal path, blocks only encrypted-chat uploads
{
  const undoRecv = installReceivePatch();
  assert.equal(api.fluxHooks.length, 1);

  const plain = { type: "MESSAGE_CREATE", message: { id: "1", channel_id: CH, author: { id: "5" }, content: "just text" } };
  assert.equal(dispatch(api, plain), plain);
  assert.equal(plain.message.content, "just text");

  const other = { type: "TYPING_START", channelId: CH };
  assert.equal(dispatch(api, other), other);

  const upload = { type: "UPLOAD_ATTACHMENT_ADD_FILES", channelId: CH, files: [{}] };
  assert.equal(dispatch(api, upload), null);
  assert.ok(diag.uploadEvents.includes("UPLOAD_ATTACHMENT_ADD_FILES"));

  setChannelEnabled(CH, false);
  assert.equal(dispatch(api, upload), upload);
  const rm = { type: "UPLOAD_ATTACHMENT_REMOVE_FILE", channelId: CH };
  assert.equal(dispatch(api, rm), rm);
  setChannelEnabled(CH, true);
  ok("receive hook returns every event; only an attach in an encrypted chat is dropped");

  // own user id comes from CONNECTION_OPEN, or from UserStore as a fallback
  const open = { type: "CONNECTION_OPEN", user: { id: "424242424242424242" } };
  assert.equal(dispatch(api, open), open);
  assert.equal(getSelfId(), "424242424242424242");
  ok("own user id is learned from CONNECTION_OPEN");

  // a throwing handler must never block or break Discord's own dispatch
  const bad = { type: "MESSAGE_CREATE", message: { get content() { throw new Error("boom"); } } };
  assert.equal(dispatch(api, bad), bad);
  ok("an error inside the hook returns the event untouched");

  undoRecv();
  assert.equal(api.fluxHooks.length, 0);
  ok("unload removes the flux hook");
}

// 8. full round trip with real crypto: encrypt on send, decrypt on receive
if (realCrypto) {
  const { toHex } = await import("../js/crypto/hex.ts");
  storage.keyV2 = toHex(new Uint8Array(32).fill(7));
  setChannelEnabled(CH, true);

  const api2 = makeApi();
  bindHost(api2);
  const sent = [];
  const MA = makeMessageActions(sent);
  api2.addModule(MA);
  const undoSend = installSendPatch();
  const undoRecv = installReceivePatch();
  dispatch(api2, { type: "CONNECTION_OPEN", user: { id: ME } });

  await MA.sendMessage(CH, { content: "round trip ✓ ünïcode" }, true, {});
  assert.equal(sent.length, 1);
  assert.ok(sent[0].content.startsWith("§"), "sent content must be ciphertext");
  assert.ok(!sent[0].content.includes("round trip"));

  const incoming = { type: "MESSAGE_CREATE", message: { id: "77", channel_id: CH, author: { id: ME }, content: sent[0].content } };
  const out = dispatch(api2, incoming);
  assert.equal(out.message.content, "🔒 round trip ✓ ünïcode");
  ok("send encrypts (v2, bound to channel + sender); receive decrypts it back");

  // an edit of that message stays encrypted even when the chat is switched off afterwards
  setChannelEnabled(CH, false);
  await MA.editMessage(CH, "77", { content: "edited text" });
  const edit = sent.find((s) => s.edit);
  assert.ok(edit.content.startsWith("§"), "edit of an encrypted message must stay encrypted");
  ok("edit of a decrypted message is re-encrypted even when the chat is off");

  undoSend();
  undoRecv();
} else {
  console.log("  --  skipped: round trip with real crypto (install @noble/* to run it)");
}

await detachStorage();
unbindHost();
console.log("\nglue tests passed.");
