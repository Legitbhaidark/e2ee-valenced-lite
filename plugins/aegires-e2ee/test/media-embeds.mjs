// Tests the chat pictures for decrypted video/audio (media.ts), the tap routing to the native
// player (player.ts) and the opt-in link previews (embeds.ts), against a FAKE native bridge.
// What Android / Java really do (frame grab, drawing, HTTP) is tested elsewhere or by CI + a phone.
//
// Run from the plugin folder:  node test/media-embeds.mjs
import assert from "node:assert/strict";
import { register } from "node:module";

let realCrypto = true;
try {
  import.meta.resolve("@noble/hashes/sha256");
  import.meta.resolve("fflate");
} catch {
  realCrypto = false;
  const hooks = `
    const NAMES = ["blake2b","deflateSync","inflateSync","ghash","hkdf","pbkdf2Async","sha1","sha256"];
    export async function resolve(spec, ctx, next) {
      if (/^@noble\\//.test(spec) || spec === "fflate") return { url: "stub:" + spec, shortCircuit: true };
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
const { attachStorage, storage } = await import("../js/storage.ts");
const { initStorage } = await import("../js/state.ts");
const { startNative, stopNative, nativeReady } = await import("../js/engine/engine.ts");
const { handleAttachments, clearMedia, mediaForUri, decryptedMedia } = await import("../js/patches/media.ts");
const { firstLinkUrl, embedFor, handleLinkPreviews, clearLinkPreviews } = await import("../js/patches/embeds.ts");

const ok = (m) => console.log("  ok  " + m);
const CH = "123456789012345678";
const AUTHOR = "555555555555555555";
const KAT_B64 = Buffer.from("9ff38392f0eda1da7689e211bae2a5a3ef7d464fa87a243411537fc5564527f8", "hex").toString("base64");
const tick = (ms = 20) => new Promise((r) => setTimeout(r, ms));

// ---------------------------------------------------------------- fake bridge + dispatcher
const calls = [];
const dispatched = [];
let previewReply = '{"title":"Hello","description":"World","image":"https://example.com/i.png","site":"Example","w":1200,"h":630}';
let thumbFails = false;

const bridge = async (name, args) => {
  calls.push({ name, args });
  switch (name) {
    case "aegires.ping": return "pong";
    case "aegires.argon2id": return KAT_B64;
    case "aegires.argon2with": return KAT_B64;
    case "aegires.argon2engine": return "c:arm64-v8a";
    case "valenced.fs.getConstants": return { cache: "/data/cache" };
    case "valenced.fs.delete": return "";
    case "aegires.openfile": return "1234";
    case "aegires.mediainfo": return args[1] === "video" ? "1080,1920" : "640,480";
    case "aegires.mediathumb":
      if (thumbFails) throw new Error("no video frame");
      return args[0] === "audio" ? "720,216" : "720,1280";
    case "aegires.linkpreview": return previewReply;
    default: throw new Error("unexpected native call " + name);
  }
};

bindHost({
  unscoped: {
    modules: { native: { callNativeMethod: bridge } },
    discord: { common: { flux: { Dispatcher: { dispatch: (e) => dispatched.push(e) } } } },
  },
});
const store = { cache: undefined, async get() { store.cache = {}; return store.cache; }, async set(v) { store.cache = v; } };
await attachStorage(store);
initStorage();
storage.keyV2 = "ab".repeat(32);
storage.attachmentsExp = true;

assert.equal(await startNative(), true);
assert.equal(nativeReady(), true);
clearMedia();
await tick();

const att = (id, filename) => ({ id, filename, url: `https://cdn.discordapp.com/attachments/${CH}/${id}/${filename}`, size: 99 });
const message = (id, attachments) => ({ id, channel_id: CH, author: { id: AUTHOR }, content: "", attachments });

// ---------------------------------------------------------------- 1. video: picture with play button
{
  const m = message("m1", [att("111", "0123456789abcdef0123.enc.mp4")]);
  handleAttachments(m, CH, AUTHOR, null);
  await tick(50);
  const thumbCall = calls.find((c) => c.name === "aegires.mediathumb");
  assert.deepEqual(thumbCall.args.slice(0, 3), ["video", "/data/cache/aegires/in/111.mp4", "/data/cache/aegires/in/111.thumb.jpg"]);
  const upd = dispatched.find((e) => e.type === "MESSAGE_UPDATE" && e.message.id === "m1");
  assert.ok(upd, "a MESSAGE_UPDATE with the rewritten attachment is dispatched");
  const a = upd.message.attachments[0];
  assert.equal(a.url, "file:///data/cache/aegires/in/111.thumb.jpg");
  assert.equal(a.proxy_url, a.url);
  assert.equal(a.content_type, "image/jpeg");
  assert.equal(a.filename, "0123456789abcdef0123.mp4.jpg");
  assert.equal(a.width, 720);
  assert.equal(a.height, 1280);
  ok("decrypted video: attachment points at the thumbnail (play button), sized like it");

  // a second pass over the same message applies the cached result at once
  const again = message("m1", [att("111", "0123456789abcdef0123.enc.mp4")]);
  handleAttachments(again, CH, AUTHOR, null);
  assert.equal(again.attachments[0].url, "file:///data/cache/aegires/in/111.thumb.jpg");
  ok("cached video is rewritten immediately on the next event");

  // tap routing: the thumbnail uri maps back to the video file
  const hit = mediaForUri("file:///data/cache/aegires/in/111.thumb.jpg?x=1");
  assert.deepEqual(hit, { path: "/data/cache/aegires/in/111.mp4", title: "Video" });
  assert.deepEqual(decryptedMedia(["111"]), hit);
  assert.deepEqual(decryptedMedia([], "m1", 0), hit);
  assert.equal(mediaForUri("file:///data/cache/aegires/in/111.mp4").path, "/data/cache/aegires/in/111.mp4");
  assert.equal(mediaForUri("file:///data/cache/aegires/in/222.png"), null);
  assert.equal(mediaForUri("file:///etc/passwd.mp4"), null);
  assert.equal(mediaForUri("https://cdn.discordapp.com/x/aegires/y.mp4"), null);
  ok("tap routing: thumbnail -> video file; images, foreign paths and http urls are not ours");
}

// ---------------------------------------------------------------- 2. video without a thumbnail falls back
{
  thumbFails = true;
  const m = message("m2", [att("112", "0123456789abcdef0123.enc.mov")]);
  handleAttachments(m, CH, AUTHOR, null);
  await tick(50);
  const upd = dispatched.find((e) => e.type === "MESSAGE_UPDATE" && e.message.id === "m2");
  const a = upd.message.attachments[0];
  assert.equal(a.url, "file:///data/cache/aegires/in/112.mov");
  assert.equal(a.content_type, "image/jpeg");
  assert.equal(a.width, 1080);
  thumbFails = false;
  ok("if the thumbnail cannot be made, the video file itself is shown as before");
}

// ---------------------------------------------------------------- 3. audio: card + title
{
  const m = message("m3", [att("113", "My Song.enc.m4a"), att("114", "0123456789abcdef0123.enc.mp3")]);
  handleAttachments(m, CH, AUTHOR, null);
  await tick(80);
  const upd = dispatched.find((e) => e.type === "MESSAGE_UPDATE" && e.message.id === "m3");
  assert.ok(upd);
  const [a, b] = upd.message.attachments;
  assert.equal(a.url, "file:///data/cache/aegires/in/113.thumb.jpg");
  assert.equal(a.width, 720);
  assert.equal(a.height, 216);
  assert.equal(a.content_type, "image/jpeg");
  const t1 = calls.filter((c) => c.name === "aegires.mediathumb" && c.args[0] === "audio");
  assert.equal(t1.find((c) => c.args[1].endsWith("113.m4a")).args[3], "My Song");
  assert.equal(t1.find((c) => c.args[1].endsWith("114.mp3")).args[3], "Audio");
  assert.deepEqual(mediaForUri(a.url), { path: "/data/cache/aegires/in/113.m4a", title: "My Song" });
  assert.equal(mediaForUri(b.url).title, "Audio");
  assert.ok(!calls.some((c) => c.name === "aegires.mediainfo" && c.args[0].endsWith("113.m4a")), "no image/video probe for audio");
  ok("decrypted audio: card picture, readable name as title, random names become 'Audio'");
}

// ---------------------------------------------------------------- 4. audio thumbnail failure is a failed attachment
{
  thumbFails = true;
  const before = dispatched.length;
  const m = message("m4", [att("115", "x.enc.mp3")]);
  handleAttachments(m, CH, AUTHOR, null);
  await tick(50);
  assert.equal(dispatched.length, before, "nothing is rewritten when an audio card cannot be made");
  thumbFails = false;
  ok("audio without a card is left alone (no half-rewritten attachment)");
}

// ---------------------------------------------------------------- 5. link url extraction
{
  const cases = [
    ["see https://example.com/a?b=1.", "https://example.com/a?b=1"],
    ["(https://example.com/x)", "https://example.com/x"],
    ["https://en.wikipedia.org/wiki/Foo_(bar)", "https://en.wikipedia.org/wiki/Foo_(bar)"],
    ["<https://example.com/no> https://example.org/yes", "https://example.org/yes"],
    ["<https://example.com/no>", null],
    ["http://insecure.example.com", null],
    ["no link here", null],
    ["a https://", null],
    ["x https://example.com/path, then more", "https://example.com/path"],
    [42, null],
  ];
  for (const [text, want] of cases) assert.equal(firstLinkUrl(text), want, String(text));
  ok("first https link: trailing punctuation, parentheses, <no embed> syntax, http and junk");

  const e = embedFor("https://example.com/p", { title: "T", description: "D", image: "https://example.com/i.png", site: "S", w: 800, h: 400 });
  assert.deepEqual(e, { type: "link", url: "https://example.com/p", title: "T", description: "D", provider: { name: "S" }, thumbnail: { url: "https://example.com/i.png", proxy_url: "https://example.com/i.png", width: 800, height: 400 } });
  const e2 = embedFor("https://e.com", { title: "T", image: "https://e.com/i.png" });
  assert.equal(e2.thumbnail.width, 1200);
  assert.equal(e2.thumbnail.height, 630);
  ok("embed object shape (size from the page, or a typical card size)");
}

// ---------------------------------------------------------------- 6. link previews: off by default, on when asked
{
  clearLinkPreviews();
  calls.length = 0;
  dispatched.length = 0;
  assert.equal(storage.linkPreviews, false);
  const off = { id: "p1", channel_id: CH, content: "x https://example.com/a" };
  handleLinkPreviews(off, "x https://example.com/a", CH);
  await tick();
  assert.equal(calls.filter((c) => c.name === "aegires.linkpreview").length, 0);
  assert.equal(off.embeds, undefined);
  ok("link previews are OFF by default: no lookup is made");

  storage.linkPreviews = true;
  const m1 = { id: "p1", channel_id: CH, content: "x https://example.com/a" };
  const m2 = { id: "p2", channel_id: CH, content: "again https://example.com/a" };
  handleLinkPreviews(m1, "x https://example.com/a", CH);
  handleLinkPreviews(m2, "again https://example.com/a", CH);
  await tick(50);
  assert.equal(calls.filter((c) => c.name === "aegires.linkpreview").length, 1, "one lookup for the same link");
  const ups = dispatched.filter((e) => e.type === "MESSAGE_UPDATE");
  assert.deepEqual(ups.map((e) => e.message.id).sort(), ["p1", "p2"]);
  assert.equal(ups[0].message.embeds[0].title, "Hello");
  assert.equal(ups[0].message.embeds[0].thumbnail.width, 1200);
  ok("on: one lookup per link, every waiting message gets its embed");

  const m3 = { id: "p3", channel_id: CH, content: "https://example.com/a" };
  handleLinkPreviews(m3, "https://example.com/a", CH);
  assert.equal(m3.embeds.length, 1);
  assert.equal(calls.filter((c) => c.name === "aegires.linkpreview").length, 1);
  ok("a cached preview is attached at once, without another lookup");

  const real = { id: "p4", channel_id: CH, content: "https://example.com/zzz", embeds: [{ type: "rich" }] };
  handleLinkPreviews(real, "https://example.com/zzz", CH);
  assert.equal(real.embeds.length, 1);
  assert.equal(real.embeds[0].type, "rich");
  ok("a message that already has an embed is left alone");

  // no data on the page: nothing is shown, and it is not asked again
  previewReply = "";
  calls.length = 0;
  dispatched.length = 0;
  const e1 = { id: "p5", channel_id: CH, content: "https://nodata.example.com/" };
  handleLinkPreviews(e1, "https://nodata.example.com/", CH);
  await tick(50);
  assert.equal(dispatched.length, 0);
  handleLinkPreviews({ id: "p6", channel_id: CH }, "https://nodata.example.com/", CH);
  await tick(20);
  assert.equal(calls.filter((c) => c.name === "aegires.linkpreview").length, 1);
  ok("a page without data gives no embed and is not looked up again");

  // a failing lookup is remembered too
  const failing = async (name, args) => { if (name === "aegires.linkpreview") throw new Error("address not public"); return bridge(name, args); };
  bindHost({ unscoped: { modules: { native: { callNativeMethod: failing } }, discord: { common: { flux: { Dispatcher: { dispatch: (e) => dispatched.push(e) } } } } } });
  const f1 = { id: "p7", channel_id: CH };
  handleLinkPreviews(f1, "https://127.0.0.1/admin", CH);
  await tick(30);
  assert.equal(f1.embeds, undefined);
  ok("a refused / failed lookup shows nothing and does not break the message");

  // turning it off in settings clears what was learned
  clearLinkPreviews();
  storage.linkPreviews = false;
}

stopNative();
clearMedia();
unbindHost();
console.log("\nmedia and link preview tests passed." + (realCrypto ? "" : " (crypto packages were stubbed)"));
