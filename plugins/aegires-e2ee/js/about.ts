// Version labels (also used for the one-time update hint). Keep PLUGIN_VERSION in step with manifest.json
// (CI checks it). Dev builds carry a "-devN" label (they go to the "beta" channel), releases do not.
declare const __DEV__: boolean;

export const DEVELOPER = "Dark (system32crowdstrikewin11)";
export const PLUGIN_VERSION = "0.1.2";
export const BUILD_NUMBER = "26110102"; // YYMM + (minor * 100 + patch); shown as text

/**
 * True only in a dev build (`valenced-plugin build --dev`, i.e. gradle -Pdev). The Valenced CLI replaces
 * __DEV__ with true/false at build time, and the bundler then drops the dev code from release bundles.
 * If the name is not replaced it does not exist, and the build counts as a release (the safe side).
 * Keep this exact shape: it is what the minifier can fold away.
 */
export const DEV_BUILD: boolean = typeof __DEV__ !== "undefined" && __DEV__ === true;

/** "26110128-release" or "26110128-dev". */
export const BUILD_ID = BUILD_NUMBER + (DEV_BUILD ? "-dev" : "-release");
