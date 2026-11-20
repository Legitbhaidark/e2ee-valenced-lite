// Tests the chat pictures for decrypted video/audio (media.ts), the tap routing to the native
// player (player.ts), against a FAKE native bridge.
// What Android / Java really do (frame grab, drawing, HTTP) is tested elsewhere or by CI + a phone.
//
// Run from the plugin folder:  node test/media-embeds.mjs
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
const { attachStorage, storage } = await import("../js/storage.ts");
const { initStorage } = await import("../js/state.ts");
const { startNative, stopNative, nativeReady } = await import("../js/engine/engine.ts");
const { handleAttachments, clearMedia, mediaForUri, decryptedMedia, placeholderIdForUri, placeholderIdFor, approveDecrypt, autoDecryptBytes } = await import("../js/patches/media.ts");

const ok = (m) => console.log("  ok  " + m);
const CH = "123456789012345678";
const AUTHOR = "555555555555555555";
const KAT_B64 = Buffer.from("9ff38392f0eda1da7689e211bae2a5a3ef7d464fa87a243411537fc5564527f8", "hex").toString("base64");
const tick = (ms = 20) => new Promise((r) => setTimeout(r, ms));

// ---------------------------------------------------------------- fake bridge + dispatcher
const calls = [];
const dispatched = [];
let thumbFails = false;
let openDelay = 0;
let openFail = "";
let progressReply = "";
const imageSizes = {}; // path suffix -> "W,H" for the fake probe

const bridge = async (name, args) => {
  calls.push({ name, args });
  switch (name) {
    case "e2eelite.ping": return "pong";
    case "e2eelite.argon2id": return KAT_B64;
    case "e2eelite.argon2with": return KAT_B64;
    case "e2eelite.argon2engine": return "c:arm64-v8a";
    case "valenced.fs.getConstants": return { cache: "/data/cache" };
    case "valenced.fs.delete": return "";
    case "e2eelite.openfile":
      if (openDelay) await new Promise((r) => setTimeout(r, openDelay));
      if (openFail) throw new Error(openFail);
      return "1234";
    case "e2eelite.progress": return progressReply;
    case "e2eelite.placeholder": return args[0] === "audio" ? "720,216" : "640,360";
    case "e2eelite.mediainfo": {
      const k = Object.keys(imageSizes).find((x) => args[0].endsWith(x));
      return k ? imageSizes[k] : args[1] === "video" ? "1080,1920" : "640,480";
    }
    case "e2eelite.mediathumb":
      if (thumbFails) throw new Error("no video frame");
      return args[0] === "audio" ? "720,216" : "720,1280";
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
  handleAttachments(m, CH, AUTHOR, []);
  await tick(50);
  const thumbCall = calls.find((c) => c.name === "e2eelite.mediathumb");
  assert.deepEqual(thumbCall.args.slice(0, 3), ["video", "/data/cache/e2ee-lite/in/111.mp4", "/data/cache/e2ee-lite/in/111.thumb.jpg"]);
  const upd = dispatched.find((e) => e.type === "MESSAGE_UPDATE" && e.message.id === "m1");
  assert.ok(upd, "a MESSAGE_UPDATE with the rewritten attachment is dispatched");
  const a = upd.message.attachments[0];
  assert.equal(a.url, "file:///data/cache/e2ee-lite/in/111.thumb.jpg");
  assert.equal(a.proxy_url, a.url);
  assert.equal(a.content_type, "image/jpeg");
  assert.equal(a.filename, "0123456789abcdef0123.mp4.jpg");
  assert.equal(a.width, 720);
  assert.equal(a.height, 1280);
  ok("decrypted video: attachment points at the thumbnail (play button), sized like it");

  // a second pass over the same message applies the cached result at once
  const again = message("m1", [att("111", "0123456789abcdef0123.enc.mp4")]);
  handleAttachments(again, CH, AUTHOR, []);
  assert.equal(again.attachments[0].url, "file:///data/cache/e2ee-lite/in/111.thumb.jpg");
  ok("cached video is rewritten immediately on the next event");

  // tap routing: the thumbnail uri maps back to the video file
  const hit = mediaForUri("file:///data/cache/e2ee-lite/in/111.thumb.jpg?x=1");
  assert.deepEqual(hit, { path: "/data/cache/e2ee-lite/in/111.mp4", title: "Video", w: 720, h: 1280 });
  assert.deepEqual(decryptedMedia(["111"]), hit);
  assert.deepEqual(decryptedMedia([], "m1", 0), hit);
  assert.equal(mediaForUri("file:///data/cache/e2ee-lite/in/111.mp4").path, "/data/cache/e2ee-lite/in/111.mp4");
  assert.equal(mediaForUri("file:///data/cache/e2ee-lite/in/222.png"), null);
  assert.equal(mediaForUri("file:///etc/passwd.mp4"), null);
  assert.equal(mediaForUri("https://cdn.discordapp.com/x/e2ee-lite/y.mp4"), null);
  ok("tap routing: thumbnail -> video file; images, foreign paths and http urls are not ours");
}

// ---------------------------------------------------------------- 2. video without a thumbnail falls back
{
  thumbFails = true;
  const m = message("m2", [att("112", "0123456789abcdef0123.enc.mov")]);
  handleAttachments(m, CH, AUTHOR, []);
  await tick(50);
  const upd = dispatched.find((e) => e.type === "MESSAGE_UPDATE" && e.message.id === "m2");
  const a = upd.message.attachments[0];
  assert.equal(a.url, "file:///data/cache/e2ee-lite/in/112.mov");
  assert.equal(a.content_type, "image/jpeg");
  assert.equal(a.width, 1080);
  thumbFails = false;
  ok("if the thumbnail cannot be made, the video file itself is shown as before");
}

// ---------------------------------------------------------------- 3. audio: card + title
{
  const m = message("m3", [att("113", "My Song.enc.m4a"), att("114", "0123456789abcdef0123.enc.mp3")]);
  handleAttachments(m, CH, AUTHOR, []);
  await tick(80);
  const upd = dispatched.filter((e) => e.type === "MESSAGE_UPDATE" && e.message.id === "m3").pop();
  assert.ok(upd);
  const [a, b] = upd.message.attachments;
  assert.equal(a.url, "file:///data/cache/e2ee-lite/in/113.thumb.jpg");
  assert.equal(a.width, 720);
  assert.equal(a.height, 216);
  assert.equal(a.content_type, "image/jpeg");
  const t1 = calls.filter((c) => c.name === "e2eelite.mediathumb" && c.args[0] === "audio");
  assert.equal(t1.find((c) => c.args[1].endsWith("113.m4a")).args[3], "My Song");
  assert.equal(t1.find((c) => c.args[1].endsWith("114.mp3")).args[3], "Audio");
  assert.deepEqual(mediaForUri(a.url), { path: "/data/cache/e2ee-lite/in/113.m4a", title: "My Song", w: 720, h: 216 });
  assert.equal(mediaForUri(b.url).title, "Audio");
  assert.ok(!calls.some((c) => c.name === "e2eelite.mediainfo" && c.args[0].endsWith("113.m4a")), "no image/video probe for audio");
  ok("decrypted audio: card picture, readable name as title, random names become 'Audio'");
}

// ---------------------------------------------------------------- 4. audio thumbnail failure is a failed attachment
{
  thumbFails = true;
  const m = message("m4", [att("115", "x.enc.mp3")]);
  handleAttachments(m, CH, AUTHOR, []);
  await tick(50);
  const last = dispatched.filter((e) => e.type === "MESSAGE_UPDATE" && e.message.id === "m4").pop();
  assert.equal(last.message.attachments[0].url, att("115", "x.enc.mp3").url, "the original file card is shown again");
  thumbFails = false;
  ok("audio without a card: the original attachment is restored, not half rewritten");
}

// ---------------------------------------------------------------- 4b. tap to decrypt (large files)
{
  assert.equal(autoDecryptBytes(), 25 * 1048576);
  storage.autoDecryptMB = "";
  assert.equal(autoDecryptBytes(), 25 * 1048576, "empty means the default");
  storage.autoDecryptMB = "junk";
  assert.equal(autoDecryptBytes(), 25 * 1048576, "junk means the default");
  storage.autoDecryptMB = 100;
  assert.equal(autoDecryptBytes(), 100 * 1048576);
  storage.autoDecryptMB = 25;

  calls.length = 0;
  const big = att("120", "0123456789abcdef0123.enc.mp4");
  big.size = 60 * 1048576;
  const m = message("m5", [big]);
  handleAttachments(m, CH, AUTHOR, []);
  handleAttachments(message("m5", [{ ...big }]), CH, AUTHOR, []); // a second event while it is being drawn
  await tick(50);
  assert.equal(calls.filter((c) => c.name === "e2eelite.openfile").length, 0, "nothing is downloaded before the tap");
  const drawn = calls.filter((c) => c.name === "e2eelite.placeholder");
  assert.equal(drawn.length, 1, "drawn once, not once per event");
  assert.deepEqual(drawn[0].args.slice(0, 4), ["video", "tap", "-1", String(60 * 1048576)]);
  const up = dispatched.filter((e) => e.type === "MESSAGE_UPDATE" && e.message.id === "m5").pop();
  const a = up.message.attachments[0];
  assert.match(a.url, /\/e2ee-lite\/in\/120\.ph\d+\.jpg$/);
  assert.equal(a.filename, "encrypted-video.jpg");
  assert.equal(a.width, 640);
  assert.equal(a.height, 360);
  ok("a file above the limit shows 'Tap to decrypt' and is NOT downloaded");

  // later events re-apply the placeholder synchronously
  const again = message("m5", [{ ...big }]);
  handleAttachments(again, CH, AUTHOR, []);
  assert.equal(again.attachments[0].url, a.url);
  ok("the placeholder is re-applied at once on the next event");

  // tap resolution
  assert.equal(placeholderIdForUri(a.url + "?x=1"), "120");
  assert.equal(placeholderIdForUri("file:///data/cache/e2ee-lite/in/999.ph1.jpg"), null);
  assert.equal(placeholderIdFor(["120"]), "120");
  assert.equal(placeholderIdFor([], "m5", 0), "120");
  assert.equal(placeholderIdFor(["nope"]), null);
  ok("tap resolution: by picture uri, attachment id, or message id + index");

  // the tap starts the work
  assert.equal(approveDecrypt("120"), true);
  assert.equal(approveDecrypt("120"), true, "a second tap while working is swallowed, not restarted");
  await tick(80);
  assert.equal(calls.filter((c) => c.name === "e2eelite.openfile").length, 1);
  const fin = dispatched.filter((e) => e.type === "MESSAGE_UPDATE" && e.message.id === "m5").pop();
  assert.equal(fin.message.attachments[0].url, "file:///data/cache/e2ee-lite/in/120.thumb.jpg");
  assert.equal(approveDecrypt("not-a-placeholder"), false);
  ok("tap decrypts the file; the finished picture (with play button) replaces the placeholder");

  // limit 0 = ask for everything
  storage.autoDecryptMB = 0;
  calls.length = 0;
  handleAttachments(message("m6", [att("121", "0123456789abcdef0123.enc.png")]), CH, AUTHOR, []);
  await tick(50);
  assert.equal(calls.filter((c) => c.name === "e2eelite.openfile").length, 0);
  assert.equal(calls.filter((c) => c.name === "e2eelite.placeholder")[0].args[0], "image");
  storage.autoDecryptMB = 25;
  ok("limit 0: even a small file waits for a tap");
}

// ---------------------------------------------------------------- 4c. progress placeholder for slow files
{
  calls.length = 0;
  openDelay = 1700;
  progressReply = "0,100";
  const sa = att("130", "0123456789abcdef0123.enc.mp4");
  sa.size = 1048576;
  handleAttachments(message("m7", [sa]), CH, AUTHOR, []);
  await tick(200);
  let work = calls.filter((c) => c.name === "e2eelite.placeholder");
  assert.equal(work.length, 1, "the skeleton is drawn at once, not after a delay");
  assert.ok(dispatched.some((e) => e.type === "MESSAGE_UPDATE" && e.message.id === "m7" && e.message.attachments[0].url.includes(".ph")), "and shown");
  assert.deepEqual(work[0].args.slice(0, 3), ["video", "work", "0"]);
  progressReply = "40,100";
  await tick(700);
  progressReply = "41,100"; // under +3 points: no redraw
  await tick(700);
  work = calls.filter((c) => c.name === "e2eelite.placeholder");
  assert.deepEqual(work.map((c) => c.args[2]), ["0", "40"], "redrawn when the percentage moved by 3 or more");
  await tick(500); // finished (1700 ms)
  const last = dispatched.filter((e) => e.type === "MESSAGE_UPDATE" && e.message.id === "m7").pop();
  assert.equal(last.message.attachments[0].url, "file:///data/cache/e2ee-lite/in/130.thumb.jpg");
  const upd0 = dispatched.filter((e) => e.type === "MESSAGE_UPDATE" && e.message.id === "m7").length;
  await tick(800); // the reload nudge
  const upds = dispatched.filter((e) => e.type === "MESSAGE_UPDATE" && e.message.id === "m7");
  assert.equal(upds.length, upd0 + 1, "one more update re-sends the finished picture");
  assert.equal(upds[upds.length - 1].message.attachments[0].url, "file:///data/cache/e2ee-lite/in/130.thumb.jpg");
  const before = calls.filter((c) => c.name === "e2eelite.placeholder").length;
  await tick(900);
  assert.equal(calls.filter((c) => c.name === "e2eelite.placeholder").length, before, "no more redraws after it finished");
  openDelay = 0;
  progressReply = "";
  ok("slow file: skeleton at once, redrawn on progress, replaced by the result (+ one reload nudge), then silent");
}

// ---------------------------------------------------------------- 4d. failed decryption brings the file card back
{
  openFail = "decrypt failed";
  const f = att("140", "0123456789abcdef0123.enc.mp4");
  handleAttachments(message("m8", [f]), CH, AUTHOR, []);
  await tick(80);
  const last = dispatched.filter((e) => e.type === "MESSAGE_UPDATE" && e.message.id === "m8").pop();
  assert.equal(last.message.attachments[0].url, f.url);
  openFail = "";
  ok("a failed decryption restores the original attachment");
}

// ---------------------------------------------------------------- 4e. several pictures in one message
{
  calls.length = 0;
  dispatched.length = 0;
  Object.assign(imageSizes, { "151.png": "1000,500", "152.png": "300,900", "153.png": "800,800" });
  const files = ["151", "152", "153"].map((id) => att(id, "0123456789abcdef0123.enc.png"));
  handleAttachments(message("m9", files), CH, AUTHOR, [{ w: 1000, h: 500 }, { w: 300, h: 900 }, { w: 800, h: 800 }]); // the sender's tag = first picture only
  await tick(120);
  const ups = dispatched.filter((e) => e.type === "MESSAGE_UPDATE" && e.message.id === "m9");
  assert.ok(ups.length >= 1, "message updated");
  const last = ups[ups.length - 1].message.attachments;
  assert.deepEqual(last.map((a) => [a.width, a.height]), [[1000, 500], [300, 900], [800, 800]]);
  // no update ever shows a decrypted picture next to a sibling that is still an unsized file card
  for (const u of ups) {
    const atts = u.message.attachments;
    assert.ok(atts.every((a) => a.url.startsWith("file://") || !atts.some((b) => b.url.startsWith("file://"))), "no half-finished mix");
  }
  assert.equal(ups.length, 1, "one update for the whole batch");
  ok("3 pictures of different sizes: each gets its own real size, delivered in one update");
}

// ---------------------------------------------------------------- 4f. size tags: one per attachment
{
  const { makeSizeTags, splitSizeTags, splitSizeTag } = await import("../js/crypto/files.ts");
  assert.equal(makeSizeTags([{ w: 1000, h: 500 }]), "[sz:1000,500]", "one picture: the classic single tag");
  assert.equal(makeSizeTags([{ w: 1000, h: 500 }, { w: 300, h: 900 }]), "[sz:1000,500][sz:300,900]");
  assert.equal(makeSizeTags([null, { w: 3, h: 4 }, null]), "[sz:0,0][sz:3,4]", "unknown sizes keep their place, trailing ones are dropped");
  assert.equal(makeSizeTags([null]), "");
  const sp = splitSizeTags("[sz:1000,500][sz:300,900][sz:800,800]hello [sz:1,2]");
  assert.deepEqual(sp.sizes, [{ w: 1000, h: 500 }, { w: 300, h: 900 }, { w: 800, h: 800 }]);
  assert.equal(sp.text, "hello [sz:1,2]");
  assert.deepEqual(splitSizeTags("no tags"), { sizes: [], text: "no tags" });
  assert.equal(splitSizeTag("[sz:1,2][sz:3,4]x").text, "[sz:3,4]x", "the old one-tag reader is unchanged");
  ok("size tags: one per attachment in order, unknown kept as [sz:0,0], text after the tags untouched");
}

stopNative();
clearMedia();
unbindHost();
console.log("\nmedia tests passed." + (realCrypto ? "" : " (crypto packages were stubbed)"));
