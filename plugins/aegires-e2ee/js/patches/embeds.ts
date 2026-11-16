// LINK PREVIEWS for decrypted messages (opt-in, "Link previews" in the plugin settings).
//
// Discord's servers only ever see "§<ciphertext>", so they never build an embed for a link inside
// an encrypted message. When this is switched on, the first https link of a decrypted message is
// looked up by the native half (Java LinkPreview: Open Graph / Twitter-card tags) and the result is
// attached to the message as an ordinary embed, through a partial MESSAGE_UPDATE like the one
// media.ts uses for files.
//
// Privacy: the lookup is made by THIS PHONE, so the linked site sees its IP address and the time,
// and the preview picture is loaded from the site directly (not through Discord's media proxy).
// That is why it is off by default. A link written as <https://...> gets no preview (Discord's
// own "no embed" syntax). Only https links are fetched, and never addresses inside a private network.
import { storage } from "../storage.ts";
import { unscoped } from "../host.ts";
import { nativeLinkPreview, nativeReady, type LinkPreviewData } from "../engine/engine.ts";
import { diag } from "../diag.ts";

const MAX_PARALLEL = 2;
const MAX_QUEUE = 30; // history loads can hold many links; the newest are looked up first
const MAX_URL = 2000;

const cache = new Map<string, LinkPreviewData | null>(); // url -> preview; null = looked up, nothing to show
const waiting = new Map<string, Array<{ id: string; channel: string }>>(); // url -> messages waiting for it
const queue: string[] = [];
const inFlight = new Set<string>(); // urls being looked up right now
let running = 0;
let generation = 0; // bumped on clear so late answers of an earlier run are ignored
const stats = { fetched: 0, empty: 0, failed: 0, shown: 0, lastError: "" };

const note = () => {
  diag.installed["link previews"] = storage.linkPreviews
    ? `on - ${stats.shown} shown, ${stats.fetched} fetched, ${stats.empty} without data, ${stats.failed} failed${stats.lastError ? " (" + stats.lastError + ")" : ""}`
    : "off";
};

/** First https link of a text that should get a preview, or null. */
export function firstLinkUrl(text: unknown): string | null {
  if (typeof text !== "string" || !text.includes("https://")) return null;
  const re = /(<?)(https:\/\/[^\s<>]+)(>?)/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(text))) {
    if (m[1] === "<" && m[3] === ">") continue; // <https://...> = "no preview"
    let url = m[2];
    // Trailing punctuation belongs to the sentence, not the link; a ")" only if the link has no "(".
    for (;;) {
      const last = url[url.length - 1];
      if (/[.,;:!?'"\]}]/.test(last) || (last === ")" && !url.includes("("))) url = url.slice(0, -1);
      else break;
    }
    if (url.length <= MAX_URL && /^https:\/\/[^/?#\s]+/.test(url)) return url;
  }
  return null;
}

/** The embed Discord draws, in the shape of the API's embed object. */
export function embedFor(url: string, d: LinkPreviewData): any {
  const e: any = { type: "link", url };
  if (d.title) e.title = d.title;
  if (d.description) e.description = d.description;
  if (d.site) e.provider = { name: d.site };
  if (d.image) {
    const w = Number(d.w) > 0 && Number(d.h) > 0 ? Number(d.w) : 1200; // no size in the page: a typical card
    const h = Number(d.w) > 0 && Number(d.h) > 0 ? Number(d.h) : 630;
    e.thumbnail = { url: d.image, proxy_url: d.image, width: w, height: h };
  }
  return e;
}

function dispatch(e: any): void {
  try {
    const d = unscoped().discord.common.flux.Dispatcher;
    if (typeof d?.dispatch === "function") void Promise.resolve(d.dispatch(e)).catch(() => {});
  } catch {
    // Discord's dispatcher is not reachable: the embed simply appears the next time the message is processed.
  }
}

function pump(): void {
  while (running < MAX_PARALLEL && queue.length) {
    const url = queue.pop()!; // newest first
    const run = generation;
    running++;
    inFlight.add(url);
    nativeLinkPreview(url)
      .then((d) => {
        if (run !== generation) return;
        cache.set(url, d);
        if (d) stats.fetched++;
        else stats.empty++;
        const list = waiting.get(url) ?? [];
        waiting.delete(url);
        if (d) for (const w of list) {
          stats.shown++;
          dispatch({ type: "MESSAGE_UPDATE", message: { id: w.id, channel_id: w.channel, embeds: [embedFor(url, d)] } });
        }
      })
      .catch((e) => {
        if (run !== generation) return;
        cache.set(url, null);
        waiting.delete(url);
        stats.failed++;
        stats.lastError = String((e as any)?.message ?? e).split("\n")[0].slice(0, 50);
      })
      .finally(() => {
        inFlight.delete(url);
        if (run === generation) running--;
        note();
        pump();
      });
  }
}

/**
 * Called for every decrypted message with its text. Attaches a cached preview at once, or queues
 * the lookup and updates the message when it arrives. Does nothing unless link previews are on.
 */
export function handleLinkPreviews(m: any, text: string, channelId: string | undefined): void {
  if (!storage.linkPreviews || !nativeReady()) return;
  if (Array.isArray(m.embeds) && m.embeds.length) return; // a real embed is already there
  const url = firstLinkUrl(text);
  if (!url) return;
  if (cache.has(url)) {
    const d = cache.get(url);
    if (d) { m.embeds = [embedFor(url, d)]; stats.shown++; }
    return;
  }
  const id = m.id != null ? String(m.id) : "";
  const channel = String(m.channel_id ?? channelId ?? "");
  if (id && channel) {
    const list = waiting.get(url) ?? [];
    if (!list.some((w) => w.id === id)) list.push({ id, channel });
    waiting.set(url, list);
  }
  if (inFlight.has(url) || queue.includes(url)) return;
  if (queue.length >= MAX_QUEUE) queue.shift();
  queue.push(url);
  pump();
}

/** Forgets everything (plugin start and stop, or when the setting is turned off). */
export function clearLinkPreviews(): void {
  generation++;
  queue.length = 0;
  inFlight.clear();
  waiting.clear();
  cache.clear();
  running = 0;
  stats.fetched = stats.empty = stats.failed = stats.shown = 0;
  stats.lastError = "";
  note();
}
