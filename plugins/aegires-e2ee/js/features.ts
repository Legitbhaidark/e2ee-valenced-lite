// Attachments are EXPERIMENTAL: sending encrypted files works through the native (Java) file
// crypto; showing received encrypted files is not built yet. The switch is off by default, and
// while it is off every upload in an encrypted chat is BLOCKED (fail closed) instead of going out
// as plaintext.
export const ATTACHMENTS_READY = true;

export const attachmentsEnabled = (storageFlag: unknown): boolean => ATTACHMENTS_READY && !!storageFlag;

export const ATTACHMENTS_BLOCKED_MSG =
  "Encrypted attachments are switched off (plugin settings > Behaviour > Attachments, experimental) - file not sent so nothing goes out unencrypted. Turn encryption off for this chat to send it unencrypted.";
