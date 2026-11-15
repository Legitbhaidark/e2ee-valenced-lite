// Stand-in for the WebView crypto engine (Phase 3). With no engine, every caller falls back to
// the plain JS code (Argon2id, AES), which is slower but gives identical bytes.
export const engineReady = (): boolean => false;

export const waitEngine = async (_ms?: number): Promise<boolean> => false;

export function call<T>(_op: string, _args?: unknown, _timeoutMs?: number): Promise<T> {
  return Promise.reject(new Error("WebView engine is not in this build"));
}

export function mountChatHost(): () => void {
  return () => {};
}

export function stopEngine(): void {}

export function EngineHost(): null {
  return null;
}
