// What the settings page does (no drawing): type a password, set or clear the key, pick the profile,
// plus the Diagnostics text and the lookup of Discord's design components. Kept out of the .tsx file
// so it runs (and is tested) in plain Node.
import { React } from "../rt.ts";
import { notify } from "../notify.ts";
import { checkPassword, clearGlobalKey, getPreset, hasGlobalKey, setGlobalPassword, setPreset } from "../state.ts";
import { storageNote } from "../storage.ts";
import { getSelfId } from "../self.ts";
import { diagLines } from "../diag.ts";
import { unscoped } from "../host.ts";

export const message = (e: any) => String(e?.message ?? e).split("\n")[0].slice(0, 120);

/** What both pages do: type a password, set or clear the key, pick the profile. */
export function useKeySettings() {
  const [pw, setPw] = React.useState("");
  const [busy, setBusy] = React.useState(false);
  const [pct, setPct] = React.useState(-1);
  const [, bump] = React.useState(0);
  const refresh = () => bump((n: number) => n + 1);
  const setKey = async () => {
    if (busy) return;
    const problem = checkPassword(pw);
    if (problem) return notify(problem);
    setBusy(true);
    try {
      await setGlobalPassword(pw, (f: number) => setPct(Math.round(f * 100)));
      setPw("");
    } catch (e) {
      notify("Set key failed: " + message(e));
    } finally {
      setBusy(false);
      setPct(-1);
      refresh();
    }
  };
  const clearKey = () => {
    clearGlobalKey();
    setPw("");
    refresh();
  };
  const pick = (index: number) => {
    setPreset(index);
    refresh();
  };
  const status = busy ? "Deriving key" + (pct >= 0 ? " " + pct + "%" : "...") : hasGlobalKey() ? "Key set." : "No key set.";
  return { pw, setPw, busy, status, setKey, clearKey, pick };
}

/** The Diagnostics text (developer builds only). */
export function diagText(): string {
  return "global key: " + (hasGlobalKey() ? "set" : "NOT SET") + "\nprofile: " + getPreset().label + (storageNote() ? "\nSTORAGE: " + storageNote() : "") + "\nown user id: " + (getSelfId() ? "known" : "NOT FOUND YET") + "\n" + diagLines().join("\n");
}

/** Discord's design components, or null if this Discord build lacks one we use. */
export function designOrNull(): any {
  try {
    const D = unscoped().discord?.design;
    return D && D.Text && D.Button && D.TextInput && D.TableRadioGroup && D.TableRadioRow ? D : null;
  } catch {
    return null;
  }
}
