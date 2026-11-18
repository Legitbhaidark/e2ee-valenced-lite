// /e2ee on | off | status  - per-chat encryption switch.
// Typed "\e2ee on" (or "/e2ee on") is caught in the sendMessage hook and never sent.
// (A real slash command crashed Discord's command search and was removed.)
import { notify } from "./notify.ts";
import { canSend, isEncryptionOn, isForced, setChannelEnabled } from "./state.ts";

const USAGE = "Usage: \\e2ee on | off | status";

export function runE2ee(arg: string, channelId: string): void {
  const mode = arg.trim().toLowerCase();
  if (!channelId) return notify("No chat is open.");
  if (mode === "status" || mode === "") {
    const on = isEncryptionOn(channelId);
    return notify(`Encryption is ${on ? "ON" : "OFF"} here.${on && !canSend(channelId) ? " No key is set, so sending is blocked - set the password in settings." : ""}\n${USAGE}`);
  }
  if (mode === "on") {
    if (!canSend(channelId)) return notify("No key for this chat yet - set the password in the plugin settings first. Encryption stays off.");
    setChannelEnabled(channelId, true);
    return notify("Encryption ON for this chat.");
  }
  if (mode === "off") {
    if (isForced(channelId)) return notify("This chat has a built-in key and is always encrypted.");
    setChannelEnabled(channelId, false);
    return notify("Encryption OFF for this chat. New messages go out as plain text.");
  }
  notify(USAGE);
}

export function interceptTyped(channelId: string, content: unknown): boolean {
  if (typeof content !== "string") return false;
  const m = /^\s*[\\\/]e2ee(?:\s+(\S+))?\s*$/i.exec(content);
  if (!m) return false;
  runE2ee(m[1] ?? "status", channelId);
  return true;
}

