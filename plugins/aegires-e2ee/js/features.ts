// Phase switches. Attachments are not ported to Valenced yet, so in an encrypted chat every
// upload is BLOCKED (fail closed) instead of going out as plaintext.
export const ATTACHMENTS_READY = false;

export const attachmentsEnabled = (storageFlag: unknown): boolean => ATTACHMENTS_READY && !!storageFlag;

export const ATTACHMENTS_BLOCKED_MSG =
  "Encrypted attachments are not available in this Valenced build yet - file not sent so nothing goes out unencrypted. Turn encryption off for this chat to send it unencrypted.";
