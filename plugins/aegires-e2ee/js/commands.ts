// /e2ee on | off | status  - per-chat encryption switch.
// 1. Real slash command (patches Discord's getBuiltInCommands; best effort).
// 2. Typed "/e2ee on" caught in the sendMessage hook (works even if 1 fails; never sent).
import { notify } from "./notify.ts";
import { diag } from "./diag.ts";
import { unscoped } from "./host.ts";
import { canSend, isEncryptionOn, isForced, setChannelEnabled } from "./state.ts";

const USAGE = "Usage: /e2ee on | off | status";

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
  const m = /^\s*\/e2ee(?:\s+(\S+))?\s*$/i.exec(content);
  if (!m) return false;
  runE2ee(m[1] ?? "status", channelId);
  return true;
}

const choice = (v: string) => ({ name: v, displayName: v, value: v });

export function installSlashCommand(): () => void {
  const { finders } = unscoped().modules;
  const [mod] = finders.lookupModule(finders.filters.withProps("getBuiltInCommands"));
  if (!mod || typeof mod.getBuiltInCommands !== "function") throw new Error("getBuiltInCommands not found");
  const cmd = {
    id: "aegires-e2ee-cmd",
    name: "e2ee",
    displayName: "e2ee",
    description: "Turn Aegires encryption on or off for this chat",
    displayDescription: "Turn Aegires encryption on or off for this chat",
    type: 1,
    inputType: 1,
    applicationId: "-1",
    options: [
      {
        name: "mode",
        displayName: "mode",
        description: "on, off or status",
        displayDescription: "on, off or status",
        type: 3,
        required: true,
        choices: [choice("on"), choice("off"), choice("status")],
      },
    ],
    execute: (args: any[], ctx: any) => {
      const mode = String(args?.find?.((a: any) => a?.name === "mode")?.value ?? "status");
      runE2ee(mode, String(ctx?.channel?.id ?? ""));
    },
  };
  const un = unscoped().patcher.after(mod, "getBuiltInCommands", (result: any) => {
    if (Array.isArray(result) && !result.some((c: any) => c?.id === cmd.id)) return [...result, cmd];
    return result;
  });
  diag.installed["slash command"] = "ok";
  return un;
}
