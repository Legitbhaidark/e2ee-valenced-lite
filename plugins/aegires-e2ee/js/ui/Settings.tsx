// Settings screen. Built from plain React Native primitives (View/Text/TextInput/Switch/...)
// instead of Discord's own form components, because those get renamed/removed between
// Discord versions while React Native's primitives do not. Colors assume Discord's dark theme.
import { React, RN, comp, reactDiagnostics } from "../rt.ts";
import { selectedChannelId } from "../patch.ts";
import { storage, storageNote } from "../storage.ts";
import { notify as showToast } from "../notify.ts";
import {
  MAX_KDF_MIB,
  MAX_KDF_PASSES,
  PRESETS,
  applyDevKdf,
  clearGlobalKey,
  getPreset,
  hasGlobalKey,
  listChannelConfigs,
  passwordRule,
  removeChannelConfig,
  setChannelEnabled,
  setChannelPassword,
  setDeveloperMode,
  setGlobalPassword,
  setPreset,
} from "../state.ts";
import { maxPlaintextBytes } from "../crypto/v2.ts";
import { FORCED_CHANNELS } from "../forced.ts";
import { getSelfId } from "../self.ts";
import { benchArgon2, fmtMs, measureKdf, runSelfTest } from "../selftest.ts";
import { getKdfOverride } from "../crypto/kdf2.ts";
import { diag, diagLines } from "../diag.ts";
import { EngineHost } from "../engine/engine.ts";
import { BUILD_NUMBER, DEVELOPER, PLUGIN_VERSION } from "../about.ts";

// React Native binds after this script is evaluated, so each primitive is resolved at render time.
const lazy = (names: string[], fallback?: () => any) => (props: any) => {
  const Real = names.map((n) => comp(n)).find(Boolean) ?? (fallback ? fallback() : null);
  return React.createElement(Real, props);
};
const View: any = lazy(["View"]);
const Text: any = lazy(["Text"]);
const ScrollView: any = lazy(["ScrollView", "View"]);
const TextInput: any = lazy(["TextInput", "Text"]);
const Touch: any = lazy(["TouchableOpacity", "Pressable", "TouchableWithoutFeedback", "View"]);

// Simple replacement if this Discord build has no Switch export.
function FallbackSwitch(props: { value: boolean; onValueChange: (v: boolean) => void }) {
  return (
    <Touch onPress={() => props.onValueChange(!props.value)}>
      <View style={{ backgroundColor: props.value ? C.accent : C.input, paddingHorizontal: 14, paddingVertical: 6, borderRadius: 14 }}>
        <Text style={{ color: "#fff", fontWeight: "700" }}>{props.value ? "ON" : "OFF"}</Text>
      </View>
    </Touch>
  );
}
const Switch: any = lazy(["Switch"], () => FallbackSwitch);

const C = { text: "#dbdee1", muted: "#949ba4", card: "#2b2d31", input: "#1e1f22", accent: "#5865f2", danger: "#da373c" };

function Button(props: { label: string; onPress: () => void; danger?: boolean; busy?: boolean; key?: any }) {
  return (
    <Touch
      disabled={props.busy}
      onPress={props.onPress}
      style={{ backgroundColor: props.danger ? C.danger : C.accent, opacity: props.busy ? 0.5 : 1, padding: 10, borderRadius: 6, marginTop: 8 }}
    >
      <Text style={{ color: "#fff", textAlign: "center", fontWeight: "600" }}>{props.label}</Text>
    </Touch>
  );
}

function Field(props: { value: string; onChange: (v: string) => void; placeholder: string; secret?: boolean }) {
  return (
    <TextInput
      value={props.value}
      onChangeText={props.onChange}
      placeholder={props.placeholder}
      placeholderTextColor={C.muted}
      secureTextEntry={!!props.secret}
      autoCapitalize="none"
      autoCorrect={false}
      style={{ backgroundColor: C.input, color: C.text, borderRadius: 6, padding: 10, marginTop: 8 }}
    />
  );
}

function Chips(props: { options: Array<[string, string]>; value: string; onChange: (v: string) => void }) {
  return (
    <View style={{ flexDirection: "row", flexWrap: "wrap", marginTop: 8 }}>
      {props.options.map(([v, label]) => (
        <Touch
          key={v}
          onPress={() => props.onChange(v)}
          style={{ backgroundColor: props.value === v ? C.accent : C.input, paddingHorizontal: 12, paddingVertical: 8, borderRadius: 16, marginRight: 8, marginBottom: 8 }}
        >
          <Text style={{ color: props.value === v ? "#fff" : C.text, fontWeight: "600" }}>{label}</Text>
        </Touch>
      ))}
    </View>
  );
}

function Card(props: { title: string; children?: any; hint?: string }) {
  return (
    <View style={{ backgroundColor: C.card, borderRadius: 8, padding: 12, marginBottom: 12 }}>
      <Text style={{ color: C.text, fontSize: 16, fontWeight: "700" }}>{props.title}</Text>
      {props.hint ? <Text style={{ color: C.muted, marginTop: 4 }}>{props.hint}</Text> : null}
      {props.children}
    </View>
  );
}

function ToggleRow(props: { label: string; value: boolean; onChange: (v: boolean) => void }) {
  return (
    <View style={{ flexDirection: "row", alignItems: "center", justifyContent: "space-between", marginTop: 10 }}>
      <Text style={{ color: C.text, flex: 1, paddingRight: 12 }}>{props.label}</Text>
      <Switch value={props.value} onValueChange={props.onChange} />
    </View>
  );
}

function currentChannelId(): string {
  try {
    return selectedChannelId();
  } catch {
    return "";
  }
}

function SettingsInner() {
  const [, bump] = React.useState(0); // manual re-render (no dependency on useProxy)
  const refresh = () => bump((n: number) => n + 1);
  const [pw, setPw] = React.useState("");
  const [busy, setBusy] = React.useState(false);
  const [chanId, setChanId] = React.useState(currentChannelId());
  const [chanPw, setChanPw] = React.useState("");
  const [testLines, setTestLines] = React.useState([] as string[]);
  const [benchMiB, setBenchMiB] = React.useState("8");
  const [benchJs, setBenchJs] = React.useState(false);
  const [benchLines, setBenchLines] = React.useState([] as string[]);
  const [kdfMem, setKdfMem] = React.useState(String(storage.devKdfMemMiB ?? 64));
  const [kdfPasses, setKdfPasses] = React.useState(String(storage.devKdfPasses ?? 3));
  const [pct, setPct] = React.useState(-1); // Argon2id progress, -1 = idle
  const onProgress = (f: number) => setPct(Math.round(f * 100));
  const [buildTaps, setBuildTaps] = React.useState({ n: 0, t: 0 });
  const tapBuild = () => {
    if (storage.devMode) return showToast("Developer options are already on");
    const now = Date.now();
    const n = now - buildTaps.t > 3000 ? 1 : buildTaps.n + 1; // taps must follow each other quickly
    if (n >= 5) {
      setBuildTaps({ n: 0, t: 0 });
      setDeveloperMode(true);
      showToast("Developer options enabled");
      refresh();
    } else {
      setBuildTaps({ n, t: now });
      if (n >= 2) showToast((5 - n) + " more " + (5 - n === 1 ? "tap" : "taps") + " to enable developer options");
    }
  };
  const busyLabel = (what: string) => what + (pct >= 0 ? ` ${pct}%` : "...");

  // Run a slow async action with a busy flag, and report failures instead of crashing.
  const run = async (what: string, fn: () => Promise<void>) => {
    setBusy(true);
    try {
      await fn();
      showToast(what + " - done");
    } catch (e) {
      showToast(what + " failed: " + String(e));
    } finally {
      setBusy(false);
      setPct(-1);
      refresh();
    }
  };

  return (
    <ScrollView style={{ flex: 1 }} contentContainerStyle={{ padding: 12 }}>
      <EngineHost />
      <Card
        title={"Global key " + (hasGlobalKey() ? "(set)" : "(not set)")}
        hint={"Used in every chat that has no channel key. Everyone in the chat must type the SAME password (at least " + passwordRule() + "), and Aegires users must use the same one in the app. The password is not saved - only the keys derived from it (v1 and v2). Deriving the v2 key (Argon2id, 64 MiB) can take a while on a phone."}
      >
        <Field value={pw} onChange={setPw} placeholder="Shared password" secret />
        <Button
          label={busy ? busyLabel("Deriving key") : "Set global key"}
          busy={busy}
          onPress={() => {
            if (!pw) return showToast("Password cannot be empty");
            run("Set global key", async () => {
              await setGlobalPassword(pw, onProgress);
              setPw("");
            });
          }}
        />
        {hasGlobalKey() ? <Button label="Clear global key" danger onPress={() => { clearGlobalKey(); showToast("Global key cleared"); refresh(); }} /> : null}
      </Card>

      <Card
        title={"Format: " + (storage.format === "v1" ? "v1 (legacy)" : "v2, " + getPreset().label)}
        hint="v2 is the current Aegires format: Argon2id keys, a fresh key per message and per file bound to the chat and the sender. Old v1 messages are always still decrypted."
      >
        {PRESETS.map((p: any) => (
          <Button
            key={p.index}
            label={(storage.format !== "v1" && getPreset().index === p.index ? "\u2714 " : "") + "v2 " + p.label + "  (max " + maxPlaintextBytes(p) + " bytes)"}
            onPress={() => { storage.format = "v2"; setPreset(p.index); refresh(); }}
          />
        ))}
        <ToggleRow label="Hide message length better (Padmé padding, up to ~12% longer; every v2 client can read it)" value={storage.padme !== false} onChange={(v) => { storage.padme = v; refresh(); }} />
      </Card>

      {Object.keys(FORCED_CHANNELS).length ? (
        <Card title="Built-in channels" hint="Aegires channels with a built-in key: always encrypted with v2 AES-128-GCM, nothing to set up. Anyone with Aegires can read them.">
          {Object.keys(FORCED_CHANNELS).map((id) => (
            <Text key={id} selectable style={{ color: C.text, marginTop: 6, fontFamily: "monospace" }}>{id}</Text>
          ))}
        </Card>
      ) : null}

      <Card title="Behaviour">
        <ToggleRow label="Encrypt outgoing messages by default (in chats without their own on/off setting)" value={!!storage.encryptByDefault} onChange={(v) => { storage.encryptByDefault = v; refresh(); }} />
        <ToggleRow label="Attachments (EXPERIMENTAL): encrypt files you send in encrypted chats, under random names. Received encrypted images are shown decrypted (other file types are not built yet). When off, uploads are blocked in encrypted chats" value={!!storage.attachmentsExp} onChange={(v) => { storage.attachmentsExp = v; refresh(); }} />
        <ToggleRow label="Show a lock icon next to the time of decrypted messages" value={!!storage.showLock} onChange={(v) => { storage.showLock = v; refresh(); }} />
        <ToggleRow label="Debug logging" value={!!storage.debug} onChange={(v) => { storage.debug = v; refresh(); }} />
      </Card>

      <Card title="Per-chat key / switch" hint="Channel ID is pre-filled with the chat you had open. Leave the password empty to only change on/off.">
        <Field value={chanId} onChange={setChanId} placeholder="Channel ID" />
        <Field value={chanPw} onChange={setChanPw} placeholder="Password for this chat only (optional)" secret />
        <Button
          label="Save for this chat"
          busy={busy}
          onPress={() => {
            if (!/^\d+$/.test(chanId)) return showToast("Channel ID must be digits only");
            run("Save chat settings", async () => {
              if (chanPw) await setChannelPassword(chanId, chanPw, onProgress);
              setChannelEnabled(chanId, true);
              setChanPw("");
            });
          }}
        />
        {listChannelConfigs().map((c) => (
          <View key={c.id} style={{ marginTop: 12, borderTopWidth: 1, borderTopColor: C.input, paddingTop: 8 }}>
            <Text style={{ color: C.text }}>{c.id}{c.hasKey ? "  (own key)" : ""}</Text>
            <ToggleRow label="Encrypt outgoing here" value={c.enabled === true} onChange={(v) => { setChannelEnabled(c.id, v); refresh(); }} />
            <Button label="Remove" danger onPress={() => { removeChannelConfig(c.id); refresh(); }} />
          </View>
        ))}
      </Card>

      <Card title="Diagnostics" hint="What the hooks are doing right now. Press Refresh after sending / receiving a message.">
        <Button label="Refresh" onPress={() => refresh()} />
        <Text selectable style={{ color: C.text, marginTop: 8, fontFamily: "monospace", fontSize: 12 }}>
          {"global key: " + (hasGlobalKey() ? "set" : "NOT SET") + "\nformat: " + (storage.format === "v1" ? "v1" : "v2 " + getPreset().label) + (storageNote() ? "\nSTORAGE: " + storageNote() : "") + "\nown user id: " + (getSelfId() ? "known" : "NOT FOUND YET") + "\n" + diagLines().join("\n")}
        </Text>
      </Card>

      <Card title="Self-test" hint="Run this first. It checks Discord has a secure random source and that this phone can decrypt messages made by the real Aegires code.">
        <Button
          label={busy ? "Running..." : "Run self-test"}
          busy={busy}
          onPress={() =>
            run("Self-test", async () => {
              const r = await runSelfTest();
              setTestLines(r.lines);
              showToast(r.ok ? "Self-test passed" : "Self-test FAILED");
            })
          }
        />
        <Button
          label={busy ? busyLabel("Measuring") : "Measure key derivation speed (real size)"}
          busy={busy}
          onPress={() =>
            run("Measure", async () => {
              const jobs = diag.engineJobs;
              const ms = await measureKdf(onProgress);
              const how = diag.engineJobs > jobs ? "natively - " + (diag.argon2Engine || "Kotlin") : "in plain JS - native: " + (diag.installed["native crypto"] ?? "not started") + (diag.engineLastError ? ", last error: " + diag.engineLastError : "");
              const o = getKdfOverride();
              setTestLines([`Argon2id ${o ? o.mKiB / 1024 + " MiB (custom), " + o.t + " passes" : "64 MiB"} took ${fmtMs(ms)} on this phone, ${how}`]);
            })
          }
        />
        {testLines.map((l: string, i: number) => (
          <Text key={i} style={{ color: l.startsWith("FAIL") ? C.danger : C.text, marginTop: 4, fontFamily: "monospace" }}>{l}</Text>
        ))}
      </Card>

      <Card
        title="Argon2id benchmark"
        hint="Runs the same Argon2id on plain JS, Kotlin/Java and the C library and shows each time, from microseconds to seconds. The native engines are timed inside the native half (median of 3 runs). Plain JS is slow: about 0.6 s per MiB on a phone."
      >
        <Chips
          options={[["1", "1 MiB"], ["8", "8 MiB"], ["32", "32 MiB"], ["64", "64 MiB (real)"]]}
          value={benchMiB}
          onChange={setBenchMiB}
        />
        <ToggleRow label="Include plain JS (slow at large sizes)" value={benchJs} onChange={setBenchJs} />
        <Button
          label={busy ? busyLabel("Benchmarking") : "Run benchmark"}
          busy={busy}
          onPress={() =>
            run("Benchmark", async () => {
              setBenchLines([]);
              await benchArgon2(Number(benchMiB), 3, benchJs, setBenchLines, onProgress);
            })
          }
        />
        {benchLines.map((l: string, i: number) => (
          <Text key={i} style={{ color: l.startsWith("FAIL") ? C.danger : C.text, marginTop: 4, fontFamily: "monospace" }}>{l}</Text>
        ))}
      </Card>

      <Card title="Plugin versions">
        <Text style={{ color: C.text, marginTop: 8 }}>{"Developer: " + DEVELOPER}</Text>
        <Text style={{ color: C.text, marginTop: 4 }}>{"Version: " + PLUGIN_VERSION}</Text>
        <Touch onPress={tapBuild} style={{ marginTop: 4 }}>
          <Text style={{ color: C.text }}>{"Build number: " + BUILD_NUMBER}</Text>
        </Touch>
        <Text style={{ color: C.muted, marginTop: 8 }}>{"Message format: Aegires v2 (v1 read-only)"}</Text>
        <Text style={{ color: C.muted, marginTop: 4 }}>{"File format: Aegires v3"}</Text>
        <Text style={{ color: C.muted, marginTop: 4 }}>{"Native module: " + (diag.installed["native crypto"] ?? "not started")}</Text>
      </Card>

      {storage.devMode ? (
        <Card title="Developer options" hint="For testing only. These options make the client less secure.">
          <ToggleRow
            label="Use the v1 legacy format for sending (AES-256-GCM, PBKDF2). Older and less secure; only for talking to old Aegires versions"
            value={storage.format === "v1"}
            onChange={(v) => { storage.format = v ? "v1" : "v2"; refresh(); }}
          />
          <ToggleRow
            label="Allow passwords as short as 1 character instead of 8. Makes the client less secure - proceed with caution"
            value={!!storage.devShortPw}
            onChange={(v) => { storage.devShortPw = v; refresh(); }}
          />
          <Text style={{ color: C.text, fontWeight: "700", marginTop: 14 }}>Argon2id engine</Text>
          <Text style={{ color: C.muted, marginTop: 4 }}>
            Which code derives your key. Auto uses the C library, then Kotlin/Java, then plain JS. A fixed engine that is not available falls back to plain JS (see Diagnostics). All engines give the same key.
          </Text>
          <Chips
            options={[["auto", "Auto"], ["c", "C library"], ["java", "Kotlin/Java"], ["js", "Plain JS"]]}
            value={String(storage.devArgonEngine ?? "auto")}
            onChange={(v) => { storage.devArgonEngine = v; applyDevKdf(); refresh(); }}
          />
          <Text style={{ color: C.text, fontWeight: "700", marginTop: 10 }}>Custom Argon2id size</Text>
          <Text style={{ color: C.danger, marginTop: 4 }}>
            Bad idea. Lowering memory or passes makes your password much easier to crack, and keys made with other values do NOT match the Aegires app or other users: nobody could read your messages and you could not read theirs. Only do this if you know what you are doing. It applies to the next key you derive (set the password again after changing it).
          </Text>
          <ToggleRow
            label={"Force custom size (standard: 64 MiB, 3 passes)"}
            value={!!storage.devKdfOn}
            onChange={(v) => {
              const apply = () => { storage.devKdfOn = v; applyDevKdf(); refresh(); };
              if (!v) return apply();
              try {
                RN.Alert.alert("Weaker, incompatible keys", "Only continue if you know what you are doing. Messages will not be readable by anyone using the standard size.", [
                  { text: "Cancel", style: "cancel" },
                  { text: "I know what I am doing", style: "destructive", onPress: apply },
                ]);
              } catch { apply(); }
            }}
          />
          <Field value={kdfMem} onChange={setKdfMem} placeholder={"Memory in MiB (1-" + MAX_KDF_MIB + ")"} />
          <Field value={kdfPasses} onChange={setKdfPasses} placeholder={"Passes (1-" + MAX_KDF_PASSES + ")"} />
          <Button
            label="Save custom size"
            onPress={() => {
              const m = Number(kdfMem), t = Number(kdfPasses);
              if (!Number.isInteger(m) || m < 1 || m > MAX_KDF_MIB) return showToast("Memory must be a whole number from 1 to " + MAX_KDF_MIB + " MiB");
              if (!Number.isInteger(t) || t < 1 || t > MAX_KDF_PASSES) return showToast("Passes must be a whole number from 1 to " + MAX_KDF_PASSES);
              storage.devKdfMemMiB = m;
              storage.devKdfPasses = t;
              applyDevKdf();
              showToast("Saved: " + m + " MiB, " + t + " passes" + (storage.devKdfOn ? "" : " (not forced yet)"));
              refresh();
            }}
          />
          <Button label="Turn developer options off" danger onPress={() => { setDeveloperMode(false); showToast("Developer options off"); refresh(); }} />
        </Card>
      ) : null}
    </ScrollView>
  );
}

function CrashReport(props: { error: any }) {
  const e = props.error;
  const msg = [String(e), String(e?.stack ?? "").slice(0, 1200), reactDiagnostics()].join("\n");
  return (
    <ScrollView style={{ flex: 1 }} contentContainerStyle={{ padding: 12 }}>
      <Text selectable style={{ color: C.danger }}>{msg}</Text>
    </ScrollView>
  );
}

// If anything in the screen throws (now or on a later re-render), show the error text on
// screen instead of Discord's "Uh Oh" crash page, so we can see what broke. React.Component is
// only available at render time, so the class is built on first use.
let BoundaryClass: any = null;
function boundary(): any {
  if (BoundaryClass) return BoundaryClass;
  if (typeof React.Component !== "function") return (BoundaryClass = SettingsInner);
  BoundaryClass = class extends React.Component {
    constructor(props: any) {
      super(props);
      (this as any).state = { error: null };
    }
    static getDerivedStateFromError(error: any) {
      return { error };
    }
    componentDidCatch(error: any) {
      console.log("[aegires-e2ee] settings crashed", error);
    }
    render() {
      return (this as any).state.error ? <CrashReport error={(this as any).state.error} /> : <SettingsInner />;
    }
  };
  return BoundaryClass;
}

export default function Settings() {
  try {
    const Boundary = boundary();
    return <Boundary />;
  } catch (e) {
    try { RN.Alert.alert("Aegires settings crashed", String(e)); } catch {}
    return null;
  }
}
