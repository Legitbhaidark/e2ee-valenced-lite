// The Aegires text-message marker. Encrypted messages look like "§<base64>"; the v2 format
// (crypto/v2.ts) defines everything after the marker.
export const PREFIX = "§"; // "§"

export function looksEncrypted(s: unknown): boolean {
  return typeof s === "string" && s.startsWith(PREFIX);
}
