// Settings page: what the plugin's gear button opens. It looks like Discord's own settings screens
// (Password, Profile) because it is built from Discord's design components (Valenced hands them to
// plugins as api.discord.design), so fonts, colors and themes always match the app.
//
// Two things only, like the Aegires app: the encryption KEY and the encryption PROFILE (preset).
// There is no on/off switch here: the E2EE button in the chat header does that, per chat.
//
// If this Discord build renames a design component, the page falls back to plain React Native
// controls (same two settings), and if anything throws it shows the error on screen instead of
// Discord's crash page. What the page does lives in settingsModel.ts; this file only draws.
import { React, comp, reactDiagnostics } from "../rt.ts";
import { PRESETS, getPreset, minPasswordLength } from "../state.ts";
import { DEV_BUILD } from "../about.ts";
import { designOrNull, diagText, message, useKeySettings } from "./settingsModel.ts";

// React Native binds after this script is evaluated, so each primitive is resolved at render time.
const lazy = (names: string[]) => (props: any) => {
  const Real = names.map((n) => comp(n)).find(Boolean);
  return React.createElement(Real, props);
};
const View: any = lazy(["View"]);
const Text: any = lazy(["Text"]);
const ScrollView: any = lazy(["ScrollView", "View"]);
const TextInput: any = lazy(["TextInput", "Text"]);
const Touch: any = lazy(["TouchableOpacity", "Pressable", "TouchableWithoutFeedback", "View"]);

function DesignSettings(props: { D: any }) {
  const { Text: DText, TextInput: DInput, Button: DButton, TableRadioGroup, TableRadioRow } = props.D;
  const s = useKeySettings();
  return (
    <ScrollView style={{ flex: 1 }} contentContainerStyle={{ padding: 16, paddingBottom: 48 }} keyboardShouldPersistTaps="handled">
      <DText variant="text-md/semibold" color="text-muted" style={{ marginBottom: 8 }}>Password</DText>
      <DInput value={s.pw} onChange={s.setPw} placeholder={minPasswordLength() + "-256 characters"} secureTextEntry maxLength={256} isDisabled={s.busy} />
      <View style={{ marginTop: 16 }}>
        <DButton text="Set key" variant="primary" size="md" loading={s.busy} disabled={s.busy} onPress={s.setKey} />
      </View>
      <View style={{ marginTop: 8 }}>
        <DButton text="Clear key" variant="secondary" size="md" disabled={s.busy} onPress={s.clearKey} />
      </View>
      <DText variant="text-md/medium" color="text-muted" style={{ marginTop: 12 }}>{s.status}</DText>

      <View style={{ marginTop: 28 }}>
        <TableRadioGroup title="Profile" value={getPreset().index} onChange={(v: any) => { if (v !== null && v !== undefined) s.pick(Number(v)); }}>
          {PRESETS.map((p: any) => (
            <TableRadioRow key={p.index} label={p.label} value={p.index} />
          ))}
        </TableRadioGroup>
      </View>

      {DEV_BUILD ? (
        <View style={{ marginTop: 28 }}>
          <DText variant="text-md/semibold" color="text-muted" style={{ marginBottom: 8 }}>Diagnostics</DText>
          <DText selectable variant="text-xs/normal" color="text-muted" style={{ fontFamily: "monospace" }}>{diagText()}</DText>
        </View>
      ) : null}
    </ScrollView>
  );
}

// ---- plain React Native fallback ------------------------------------------------------------------

const C = { text: "#dbdee1", muted: "#949ba4", input: "#1e1f22", accent: "#5865f2", secondary: "#4e5058", row: "#2b2d31" };

function PlainButton(props: { label: string; onPress: () => void; secondary?: boolean; busy?: boolean }) {
  return (
    <Touch disabled={props.busy} onPress={props.onPress} style={{ backgroundColor: props.secondary ? C.secondary : C.accent, opacity: props.busy ? 0.5 : 1, padding: 12, borderRadius: 8, marginTop: 8 }}>
      <Text style={{ color: "#fff", textAlign: "center", fontWeight: "600" }}>{props.label}</Text>
    </Touch>
  );
}

function PlainSettings(props: { error?: any }) {
  const s = useKeySettings();
  const current = getPreset().index;
  return (
    <ScrollView style={{ flex: 1 }} contentContainerStyle={{ padding: 16, paddingBottom: 48 }} keyboardShouldPersistTaps="handled">
      {props.error ? <Text style={{ color: C.muted, marginBottom: 12 }}>{"Discord's settings components could not be drawn (" + message(props.error) + "), so this is the plain version."}</Text> : null}
      <Text style={{ color: C.muted, fontWeight: "600", marginBottom: 8 }}>Password</Text>
      <TextInput
        value={s.pw}
        onChangeText={s.setPw}
        placeholder={minPasswordLength() + "-256 characters"}
        placeholderTextColor={C.muted}
        secureTextEntry
        maxLength={256}
        editable={!s.busy}
        autoCapitalize="none"
        autoCorrect={false}
        style={{ backgroundColor: C.input, color: C.text, borderRadius: 8, padding: 12 }}
      />
      <PlainButton label={s.busy ? "Setting key..." : "Set key"} busy={s.busy} onPress={s.setKey} />
      <PlainButton label="Clear key" secondary busy={s.busy} onPress={s.clearKey} />
      <Text style={{ color: C.muted, marginTop: 12 }}>{s.status}</Text>
      <Text style={{ color: C.muted, fontWeight: "600", marginTop: 28, marginBottom: 8 }}>Profile</Text>
      {PRESETS.map((p: any) => (
        <Touch key={p.index} onPress={() => s.pick(p.index)} style={{ backgroundColor: C.row, padding: 14, marginBottom: 2 }}>
          <Text style={{ color: C.text }}>{(p.index === current ? "\u25CF  " : "\u25CB  ") + p.label}</Text>
        </Touch>
      ))}
      {DEV_BUILD ? <Text selectable style={{ color: C.muted, marginTop: 28, fontFamily: "monospace", fontSize: 12 }}>{diagText()}</Text> : null}
    </ScrollView>
  );
}

// ---- error boundary and entry point ---------------------------------------------------------------

function CrashReport(props: { error: any }) {
  const e = props.error;
  const msg = [String(e), String(e?.stack ?? "").slice(0, 1200), reactDiagnostics()].join("\n");
  return (
    <ScrollView style={{ flex: 1 }} contentContainerStyle={{ padding: 12 }}>
      <Text selectable style={{ color: "#da373c" }}>{msg}</Text>
    </ScrollView>
  );
}

// If a child throws, draw props.fallback(error) instead of Discord's "Uh Oh" crash page.
// React.Component only exists at render time, so the class is built on first use.
let BoundaryClass: any = null;
function boundary(): any {
  if (BoundaryClass) return BoundaryClass;
  if (typeof React.Component !== "function") return (BoundaryClass = (p: any) => p.children);
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
      const error = (this as any).state.error;
      return error ? (this as any).props.fallback(error) : (this as any).props.children;
    }
  };
  return BoundaryClass;
}

export default function Settings() {
  const Boundary = boundary();
  const D = designOrNull();
  return (
    <Boundary
      fallback={(e: any) => (
        <Boundary fallback={(e2: any) => <CrashReport error={e2} />}>
          <PlainSettings error={e} />
        </Boundary>
      )}
    >
      {D ? <DesignSettings D={D} /> : <PlainSettings />}
    </Boundary>
  );
}
