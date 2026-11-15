// React / React Native for the UI and for Alerts. Valenced binds both late (after this script is
// evaluated), so they are lazy proxies: every property read resolves the real module at that moment.
import { reactModule, rnModule } from "./host.ts";

function lazy(get: () => any): any {
  return new Proxy({} as any, {
    get: (_t, k) => {
      const m = get();
      return m ? m[k] : undefined;
    },
    has: (_t, k) => {
      const m = get();
      return !!m && k in m;
    },
  });
}

export const React: any = lazy(reactModule);
export const RN: any = lazy(rnModule);

/** React Native component by name; undefined if this Discord build lacks it. */
export function comp(name: string): any {
  try {
    return rnModule()?.[name] ?? undefined;
  } catch {
    return undefined;
  }
}

export function missingComponents(names: string[]): string {
  return names.filter((n) => !comp(n)).join(",") || "none";
}

/** Short description of what we found, for the on-screen error report. */
export function reactDiagnostics(): string {
  const r = reactModule();
  return [
    "react: " + (r ? "createElement=" + typeof r.createElement + " useState=" + typeof r.useState : "NOT FOUND"),
    "react native: " + (rnModule() ? "found" : "NOT FOUND"),
    "missing RN components: " + missingComponents(["View", "Text", "ScrollView", "TextInput", "Switch", "TouchableOpacity", "Pressable"]),
  ].join("\n");
}
