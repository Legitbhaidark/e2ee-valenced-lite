// Settings entry point. The settings themselves are the native (Kotlin) screen - SettingsScreen.kt and
// DevScreen.kt - so this file only has:
//   - the launcher the plugin's gear button opens: it opens the native screen at once and steps back
//     when that screen is closed, and
//   - a small fallback screen for when the native module is missing (set the global key, and in dev
//     builds read the Diagnostics).
// Built from plain React Native primitives, because Discord's own form components get renamed between
// versions. Colors assume Discord's dark theme.
import { React, comp, reactDiagnostics } from "../rt.ts";
import { storage, storageNote } from "../storage.ts";
import { notify as showToast } from "../notify.ts";
import { getPreset, hasGlobalKey, passwordRule, setGlobalPassword } from "../state.ts";
import { getSelfId } from "../self.ts";
import { diagLines } from "../diag.ts";
import { EngineHost, nativeReady } from "../engine/engine.ts";
import { openNativeSettings } from "../nativeSettings.ts";
import { DEV_BUILD } from "../about.ts";
import { unscoped } from "../host.ts";

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

function Card(props: { title: string; children?: any; hint?: string }) {
  return (
    <View style={{ backgroundColor: C.card, borderRadius: 8, padding: 12, marginBottom: 12 }}>
      <Text style={{ color: C.text, fontSize: 16, fontWeight: "700" }}>{props.title}</Text>
      {props.hint ? <Text style={{ color: C.muted, marginTop: 4 }}>{props.hint}</Text> : null}
      {props.children}
    </View>
  );
}

function Fallback(props: { why: string; retry: () => void }) {
  const [, bump] = React.useState(0);
  const refresh = () => bump((n: number) => n + 1);
  const [pw, setPw] = React.useState("");
  const [busy, setBusy] = React.useState(false);
  const [pct, setPct] = React.useState(-1);
  return (
    <ScrollView style={{ flex: 1 }} contentContainerStyle={{ padding: 12 }}>
      <EngineHost />
      <Card title="Settings" hint={props.why || "The native settings screen could not be opened."}>
        <Button label="Try again" onPress={props.retry} />
        {!nativeReady() ? (
          <>
            <Text style={{ color: C.muted, marginTop: 8 }}>{"Native module not loaded. You can still set the global key here (at least " + passwordRule() + "):"}</Text>
            <Field value={pw} onChange={setPw} placeholder="Shared password" secret />
            <Button
              label={busy ? "Deriving key" + (pct >= 0 ? " " + pct + "%" : "...") : "Set global key"}
              busy={busy}
              onPress={async () => {
                if (!pw) return showToast("Password cannot be empty");
                setBusy(true);
                try {
                  await setGlobalPassword(pw, (f: number) => setPct(Math.round(f * 100)));
                  setPw("");
                  showToast("Set global key - done");
                } catch (e) {
                  showToast("Set global key failed: " + String(e));
                } finally {
                  setBusy(false);
                  setPct(-1);
                  refresh();
                }
              }}
            />
          </>
        ) : null}
      </Card>
      {DEV_BUILD ? (
        <Card title="Diagnostics" hint="What the hooks are doing right now. Press Refresh after sending / receiving a message.">
          <Button label="Refresh" onPress={() => refresh()} />
          <Text selectable style={{ color: C.text, marginTop: 8, fontFamily: "monospace", fontSize: 12 }}>
            {"global key: " + (hasGlobalKey() ? "set" : "NOT SET") + "\nformat: " + (storage.format === "v1" ? "v1" : "v2 " + getPreset().label) + (storageNote() ? "\nSTORAGE: " + storageNote() : "") + "\nown user id: " + (getSelfId() ? "known" : "NOT FOUND YET") + "\n" + diagLines().join("\n")}
          </Text>
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

// If anything in the screen throws, show the error text on screen instead of Discord's "Uh Oh" crash
// page. React.Component is only available at render time, so the class is built on first use.
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
      return (this as any).state.error ? <CrashReport error={(this as any).state.error} /> : (this as any).props.children;
    }
  };
  return BoundaryClass;
}

/** Navigation handle of this screen (to leave it again), if Valenced exposes one. */
function useNav(): any {
  try {
    const hook = unscoped().externals?.ReactNavigation?.ReactNavigationNative?.useNavigation;
    return typeof hook === "function" ? hook() : null;
  } catch {
    return null;
  }
}

/** What the plugin's gear button opens: the native screen at once, and a step back when it closes. */
export default function Settings() {
  const nav = useNav();
  const [mode, setMode] = React.useState("opening");
  const [why, setWhy] = React.useState("");
  const open = () => {
    setMode("opening");
    openNativeSettings(() => {
      setMode("closed");
      try { nav?.goBack?.(); } catch {}
    }).then((err: string) => {
      if (err) {
        setWhy(err);
        setMode("fallback");
      }
    });
  };
  React.useEffect(() => { open(); }, []);
  if (mode === "fallback") {
    const Boundary = boundary();
    return (
      <Boundary>
        <Fallback why={why} retry={open} />
      </Boundary>
    );
  }
  return (
    <View style={{ flex: 1, padding: 12 }}>
      <Text style={{ color: C.muted }}>{mode === "closed" ? "Settings closed." : "Opening settings..."}</Text>
      <Button label="Open settings" onPress={open} />
    </View>
  );
}
