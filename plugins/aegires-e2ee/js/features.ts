// Encrypted attachments are part of the plugin (no switch): files sent in an encrypted chat are
// encrypted by the native (Java) file crypto, received ones are shown decrypted. An upload that is
// not provably encrypted is BLOCKED (fail closed) instead of going out as plaintext.
export const ATTACHMENTS_READY = true;

export const attachmentsEnabled = (_unused?: unknown): boolean => ATTACHMENTS_READY;

export const ATTACHMENTS_BLOCKED_MSG =
  "The file could not be encrypted - not sent, so nothing goes out unencrypted. Turn encryption off for this chat to send it unencrypted.";
