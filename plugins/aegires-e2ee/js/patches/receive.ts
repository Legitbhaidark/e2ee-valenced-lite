// INCOMING: turn "§<base64>" back into readable text before Discord's message store and UI see it.
//
// Every message that reaches the app (new message, edit, history load, search, pins) is announced
// on Discord's Flux event bus as an event such as
//   { type: "MESSAGE_CREATE", message: {...} }  or  { type: "LOAD_MESSAGES_SUCCESS", messages: [...] }
// Valenced lets a plugin see every event before it is dispatched (onAnyFluxEventDispatched). We
// rewrite the message objects IN PLACE and return the event. No UI component is patched. Nothing
// is written back to Discord: the server only ever sees ciphertext.
//
// Valenced's rule: a hook that returns nothing BLOCKS the event. So the hook returns the event
// on every path except the one that deliberately drops an upload.
import { noteFound, selectedChannelId } from "../patch.ts";
import { unscoped } from "../host.ts";
import { storage } from "../storage.ts";
import { decryptText, looksEncrypted } from "../crypto/wire.ts";
import { splitSizeTag } from "../crypto/files.ts";
import { decryptMessage as decryptV2, messagePreset } from "../crypto/v2.ts";
import { LOCK, getReceiveKeys, lockMode, getReceiveKeysV2, isEncryptionOn } from "../state.ts";
import { noteSelfId } from "../self.ts";
import { notify } from "../notify.ts";
import { ATTACHMENTS_BLOCKED_MSG, attachmentsEnabled } from "../features.ts";
import { encryptAttachments } from "../outgoing.ts";
import { clearMedia, handleAttachments } from "./media.ts";
import { diag } from "../diag.ts";

/** Ids of messages we decrypted, so edits of them stay encrypted (see send.ts). */
const encryptedIds = new Set<string>();

export function wasEncrypted(messageId: string): boolean {
  return encryptedIds.has(messageId);
}

function remember(id: unknown): void {
  if (typeof id !== "string" && typeof id !== "number") return;
  encryptedIds.add(String(id));
  if (encryptedIds.size > 5000) encryptedIds.delete(encryptedIds.values().next().value as string);
}

// v2 binds each ciphertext to (channel id, AUTHOR id). Partial MESSAGE_UPDATE events can omit
// the author, so remember who wrote what from earlier events.
const authorById = new Map<string, string>();

// The sender's [sz:W,H] size per message: later partial updates of the message carry no text.
const tagById = new Map<string, { w: number; h: number }>();

function authorOf(m: any): string | undefined {
  const id = m.author?.id;
  if (id && m.id) {
    authorById.set(String(m.id), String(id));
    if (authorById.size > 5000) authorById.delete(authorById.keys().next().value as string);
  }
  return id ? String(id) : m.id ? authorById.get(String(m.id)) : undefined;
}

type Outcome = { plain: string } | "nokey" | "noauthor" | "wrong";

/** Tries v2 (when the header says so and the author is known), then v1. */
function tryDecrypt(content: string, channelId: string | undefined, authorId: string | undefined): Outcome {
  const v2keys = getReceiveKeysV2(channelId);
  const v1keys = getReceiveKeys(channelId);
  if (!v2keys.length && !v1keys.length) return "nokey";
  const isV2 = !!messagePreset(content);
  if (isV2 && channelId && authorId) {
    for (const key of v2keys) {
      const plain = decryptV2(content, key, channelId, authorId);
      if (plain !== null) return { plain };
    }
  }
  // A v1 ciphertext starts with a random IV, so it can look like a v2 header by chance.
  for (const key of v1keys) {
    const plain = decryptText(content, key);
    if (plain !== null) return { plain };
  }
  return isV2 && v2keys.length && (!channelId || !authorId) ? "noauthor" : "wrong";
}

function decryptMessage(m: any, fallbackChannelId?: string): void {
  if (!m || typeof m !== "object") return;
  const channelId: string | undefined = m.channel_id ?? m.channelId ?? fallbackChannelId;
  const authorId = authorOf(m);
  let tag: { w: number; h: number } | null = null; // the sender's [sz:W,H] pixel size

  if (looksEncrypted(m.content)) {
    diag.encSeen++;
    const r = tryDecrypt(m.content, channelId, authorId);
    if (typeof r === "object") {
      const plain = r.plain;
      diag.decrypted++;
      remember(m.id);
      // Aegires puts "[sz:W,H]" in front of image captions; hide it from the visible text.
      const sp = splitSizeTag(plain);
      if (sp.width !== null && sp.width > 0 && sp.height > 0) tag = { w: sp.width, h: sp.height };
      m.content = sp.text || sp.width === null ? (storage.showLock && lockMode.front ? LOCK : "") + sp.text : "";
    } else if (r === "nokey") {
      diag.noKey++;
    } else if (r === "noauthor") {
      diag.noAuthor++;
    } else {
      diag.wrongKey++;
    }
  }
  if (m.id != null) {
    const key = String(m.id);
    if (tag) {
      tagById.set(key, tag);
      if (tagById.size > 2000) tagById.delete(tagById.keys().next().value as string);
    } else {
      tag = tagById.get(key) ?? null;
    }
  }
  if (Array.isArray(m.attachments) && m.attachments.length) handleAttachments(m, channelId, authorId, tag);
  // Reply previews ("replying to ...") carry their own copy of the original message.
  if (m.referenced_message) decryptMessage(m.referenced_message, channelId);
  // Forwarded messages carry snapshots of the original.
  if (Array.isArray(m.message_snapshots)) {
    for (const s of m.message_snapshots) decryptMessage(s?.message, channelId);
  }
}

const MARK = "__aegiresEncrypted";

/** Discord's dispatcher, to send a held event again once its files are encrypted. */
function dispatcher(): ((e: any) => any) | null {
  try {
    const d = unscoped().discord.common.flux.Dispatcher;
    return typeof d?.dispatch === "function" ? (e: any) => d.dispatch(e) : null;
  } catch {
    return null;
  }
}

/**
 * Files being attached in an encrypted chat are encrypted right away, before any upload code
 * sees them: the event is held back, every file is replaced by its encrypted copy (random name,
 * "x.enc.ext", Aegires format) and the event is dispatched again with a marker. So only
 * ciphertext can leave the phone. If anything is unknown or fails, the file is simply not
 * attached. Returns true to drop the original event.
 */
function interceptAttach(event: any, type: string): boolean {
  if (!/UPLOAD|ATTACH/.test(type)) return false;
  if (!diag.uploadEvents.includes(type) && diag.uploadEvents.length < 10) diag.uploadEvents.push(type);
  if (!/ADD/.test(type) || event[MARK]) return false;
  const channelId = String(event.channelId ?? event.channel_id ?? selectedChannelId() ?? "");
  if (!channelId || !isEncryptionOn(channelId)) return false;
  if (!attachmentsEnabled(storage.attachmentsExp)) {
    notify(ATTACHMENTS_BLOCKED_MSG);
    return true;
  }
  diag.installed["attach event"] = `${type} keys=${Object.keys(event).join(",")}`.slice(0, 140);
  const files = [event.files, event.uploads, event.items, event.attachments].find((x) => Array.isArray(x) && x.length);
  if (!files) {
    notify("Unknown attachment format, so it cannot be encrypted - file not attached. Turn encryption off for this chat to send it unencrypted, and report the Diagnostics output.");
    return true;
  }
  const dispatch = dispatcher();
  if (!dispatch) {
    notify("Could not reach Discord's dispatcher, so the file cannot be encrypted - file not attached.");
    return true;
  }
  encryptAttachments(channelId, files)
    .then((ok) => { if (ok) dispatch({ ...event, [MARK]: true }); })
    .catch((e) => console.log("[aegires-e2ee] attach error", e));
  return true;
}

function handleEvent(event: any): boolean {
  diag.events++;
  if (!event || typeof event !== "object") return false;
  // Learn our own user id (v2 binds what we send to it) from login / profile events.
  if (event.type === "CONNECTION_OPEN" || event.type === "CURRENT_USER_UPDATE") noteSelfId(event.user?.id);
  if (interceptAttach(event, String(event.type))) return true;
  if (!event.message && !event.messages) return false;
  // Any event that carries message(s) is processed, whatever its name, so a renamed event
  // type in a new Discord version cannot silently disable decryption.
  diag.msgEvents++;
  const type = String(event.type);
  if (Object.keys(diag.types).length < 12 || diag.types[type]) diag.types[type] = (diag.types[type] ?? 0) + 1;

  if (event.message) decryptMessage(event.message, event.channelId);
  if (Array.isArray(event.messages)) {
    for (const m of event.messages) {
      if (Array.isArray(m)) m.forEach((x) => decryptMessage(x, event.channelId)); // SEARCH_FINISH groups
      else decryptMessage(m, event.channelId);
    }
  }
  return false;
}

export function installReceivePatch(): () => void {
  const flux = unscoped().discord.flux;
  const hook = flux?.onAnyFluxEventDispatched;
  noteFound("flux event hook", typeof hook === "function");
  if (typeof hook !== "function") throw new Error("Valenced onAnyFluxEventDispatched not found");

  clearMedia(); // decrypted files left by an earlier session
  const unpatch = hook((event: any) => {
    try {
      if (handleEvent(event)) return undefined; // drop this event
    } catch (e) {
      // Never let a bug here break Discord's own event handling.
      console.log("[aegires-e2ee] receive error", e);
    }
    return event;
  });
  return () => {
    unpatch();
    clearMedia();
    encryptedIds.clear();
    authorById.clear();
  };
}
