// OUTGOING: encrypt the text of a message right before Discord sends it.
//
// Discord's mobile app funnels every chat message through
// MessageActions.sendMessage(channelId, message, waitForChannelReady?, options?) (and
// editMessage for edits). We wrap both: rewrite message.content into "§<base64>" and call the
// real function, or refuse to call it at all.
//
// Several modules can export these functions (re-exports), and patching only one would let
// plaintext out through the other, so every matching module is patched. prepare() skips content
// that is already encrypted, so a message that passes two patched layers is not encrypted twice.
import { instead, noteFound, whenFns } from "../patch.ts";
import { notify, quiet } from "../notify.ts";
import { storage } from "../storage.ts";
import { diag } from "../diag.ts";
import { ATTACHMENTS_BLOCKED_MSG, attachmentsEnabled } from "../features.ts";
import { isEncryptedFileName } from "../crypto/files.ts";
import { LOCK, getSendKey, hasGlobalKey, isEncryptionOn } from "../state.ts";
import { BLOCK, captionSizeTag, prepare, uploadFileName } from "../outgoing.ts";
import { wasEncrypted } from "./receive.ts";
import { interceptTyped } from "../commands.ts";

/** Attachments riding along with sendMessage, if any. */
function attachmentsOf(message: any, options: any): any[] {
  for (const list of [options?.attachmentsToUpload, options?.uploads, message?.attachmentsToUpload]) {
    if (Array.isArray(list) && list.length) return list;
  }
  return [];
}

export function installSendPatch(): () => void {
  const undo: Array<() => void> = [];
  const patched = new Set<any>();
  noteFound("sendMessage module", false);

  const stop = whenFns(["sendMessage", "receiveMessage"], (MessageActions) => {
    if (patched.has(MessageActions)) return;
    patched.add(MessageActions);
    noteFound("sendMessage module", true);

    undo.push(
      instead("sendMessage", MessageActions, (args, orig) => {
        const [channelId, message, , options] = args;
        diag.sendCalls++;
        if (!message || typeof channelId !== "string") {
          diag.lastSend = "hook hit but unexpected args";
          return orig(...args);
        }
        if (interceptTyped(channelId, message.content)) return Promise.resolve();
        const files = attachmentsOf(message, options);
        let tag = "";
        if (files.length && isEncryptionOn(channelId)) {
          // Files were encrypted when they were attached (receive.ts). Anything that is not
          // provably encrypted (or the switch being off) stops the whole send.
          const names = files.map(uploadFileName);
          if (!attachmentsEnabled(storage.attachmentsExp) || !names.every((n) => isEncryptedFileName(n))) {
            diag.lastSend = `with ${files.length} attachment(s) -> BLOCKED (names: ${names.join(",").slice(0, 60) || "unreadable"})`;
            notify(ATTACHMENTS_BLOCKED_MSG);
            return Promise.resolve();
          }
          tag = captionSizeTag(files);
        }
        // The size tag must ride along even when there is no caption: Aegires reads the picture size
        // only from it, so an image sent without text used to arrive without a size.
        const out = prepare(channelId, tag ? tag + (typeof message.content === "string" ? message.content : "") : message.content);
        diag.lastSend = `on=${isEncryptionOn(channelId)} key=${!!getSendKey(channelId)} -> ${out === BLOCK ? "BLOCKED" : out === null ? "sent unchanged" : "encrypted"}`;
        if (storage.debug) console.log("[aegires-e2ee] sendMessage", channelId, diag.lastSend, options ? Object.keys(options) : "");
        if (out === BLOCK) return Promise.resolve();
        if (out !== null) message.content = out;
        return orig(...args);
      }),
    );

    if (typeof MessageActions.editMessage === "function") {
      undo.push(
        instead("editMessage", MessageActions, (args, orig) => {
          // args = [channelId, messageId, message]
          const [channelId, messageId, message] = args;
          if (message && typeof channelId === "string" && typeof message.content === "string") {
            // The edit box is pre-filled with the DECRYPTED text, which may start with our lock
            // marker. Remove it so we do not encrypt the marker itself.
            if (message.content.startsWith(LOCK)) message.content = message.content.slice(LOCK.length);
            // An edit of an encrypted message stays encrypted, even if the chat was switched off
            // since: otherwise the edit would publish the secret text in plaintext.
            const out = prepare(channelId, message.content, wasEncrypted(String(messageId)));
            if (out === BLOCK) return Promise.resolve();
            if (out !== null) message.content = out;
          }
          return orig(...args);
        }),
      );
    }
  });

  // If the module never shows up, sending is NOT encrypted. Say so when it matters.
  const watchdog = setTimeout(() => {
    if (patched.size) return;
    diag.installed["send patch"] = "FAILED: sendMessage module not found - sending is NOT encrypted";
    if (hasGlobalKey() || storage.encryptByDefault) notify("Aegires E2EE could not hook message sending - your messages are NOT being encrypted.");
    else quiet("sendMessage module not found");
  }, 20000);

  return () => {
    clearTimeout(watchdog);
    stop();
    undo.splice(0).reverse().forEach((u) => {
      try { u(); } catch {}
    });
    patched.clear();
  };
}
