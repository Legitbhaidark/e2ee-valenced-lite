// An encrypted batch of files is sent as ONE MESSAGE PER FILE (the Aegires protocol: one file and one
// "[sz:W,H]" tag per message). outgoing.ts is replaced by a tiny fake, so no crypto packages are needed.
//   node test/send-split.mjs
import assert from "node:assert/strict";
import { register } from "node:module";

const hooks = `
  const NAMES = ["blake2b","ghash","hkdf","pbkdf2Async","sha1","sha256"];
  export async function resolve(spec, ctx, next) {
    if (/^@noble\\//.test(spec)) return { url: "stub:" + spec, shortCircuit: true };
    if (/\\/outgoing\\.ts$/.test(spec)) return { url: "stub:outgoing", shortCircuit: true };
    return next(spec, ctx);
  }
  export async function load(url, ctx, next) {
    if (url === "stub:outgoing") return { format: "module", shortCircuit: true, source: \`
      export const BLOCK = Symbol("block");
      export const uploadFileName = (u) => String(u.filename ?? "");
      export const captionSizeTag = (files) => files.map((f) => f.size ? "[sz:" + f.size + "]" : "").join("");
      export const prepare = (ch, text) => "§" + text;
      export const encryptAttachments = async () => true;\` };
    if (url.startsWith("stub:")) return { format: "module", shortCircuit: true, source: NAMES.map((n) => "export const " + n + " = () => { throw new Error('stub'); };").join("\\n") };
    return next(url, ctx);
  }`;
register("data:text/javascript," + encodeURIComponent(hooks), import.meta.url);

const { bindHost, unbindHost } = await import("../js/host.ts");
const { attachStorage, detachStorage, storage } = await import("../js/storage.ts");
const { initStorage, setChannelEnabled } = await import("../js/state.ts");
const { installSendPatch } = await import("../js/patches/send.ts");
const ok = (m) => console.log("  ok  " + m);
const CH = "123456789012345678";

const registry = [];
const api = {
  unscoped: {
    modules: {
      finders: {
        filters: { withProps: (...props) => ({ props }) },
        getModules(f, cb) { registry.forEach((m) => f.props.every((p) => p in m) && cb(m)); return () => {}; },
      },
    },
  },
};
// the real patcher is needed: borrow the one from glue's fake by implementing instead() directly
const sent = [];
const MA = {
  receiveMessage() {},
  editMessage() {},
  sendMessage: async (ch, msg, _w, opts) => { sent.push({ msg: { ...msg }, opts }); },
};
registry.push(MA);
let origSend = MA.sendMessage;
api.unscoped.patcher = {
  instead(obj, name, cb) { const o = obj[name]; obj[name] = (...a) => cb(a, o); return () => (obj[name] = o); },
  before() { return () => {}; },
  after() { return () => {}; },
};
bindHost(api);
const store = { c: undefined, async get() { store.c = {}; return store.c; }, async set(v) { store.c = v; } };
await attachStorage(store);
initStorage();
storage.attachmentsExp = true;
setChannelEnabled(CH, true);
const undo = installSendPatch();

const f = (n, size) => ({ filename: n + ".enc.png", size });
const files = [f("0123456789abcdef0001", "1000,500"), f("0123456789abcdef0002", "300,900"), f("0123456789abcdef0003", "800,800")];
await MA.sendMessage(CH, { content: "caption", nonce: "n1" }, true, { attachmentsToUpload: files });
assert.equal(sent.length, 3, "one message per file");
assert.deepEqual(sent.map((s) => s.msg.content), ["§[sz:1000,500]caption", "§[sz:300,900]", "§[sz:800,800]"], "caption with the first; each has the size of its own file");
assert.deepEqual(sent.map((s) => s.opts.attachmentsToUpload.map((x) => x.filename)), files.map((x) => [x.filename]), "each message uploads only its own file");
assert.equal(sent[0].msg.nonce, "n1");
assert.equal(sent[1].msg.nonce, undefined);
assert.equal(sent[2].msg.nonce, undefined);
ok("batch of 3: three messages, own file + own single tag each, caption on the first, fresh nonces");

sent.length = 0;
await MA.sendMessage(CH, { content: "one" }, true, { attachmentsToUpload: [files[1]] });
assert.deepEqual(sent.map((s) => s.msg.content), ["§[sz:300,900]one"]);
ok("a single file is sent exactly as before");

undo();
await detachStorage();
unbindHost();
console.log("\nsend split test passed.");
