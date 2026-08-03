const OFFLINE_ACCESS_KEY = "reader.offlineAccess.v1";

interface OfflineAccessHint {
  username: string | null;
}

export function rememberOfflineReaderAccess(username: string | null): void {
  if (typeof localStorage === "undefined") return;
  localStorage.setItem(OFFLINE_ACCESS_KEY, JSON.stringify({ username } satisfies OfflineAccessHint));
}

export function clearOfflineReaderAccess(): void {
  if (typeof localStorage !== "undefined") localStorage.removeItem(OFFLINE_ACCESS_KEY);
}

export function offlineReaderAccessHint(): OfflineAccessHint | null {
  if (typeof localStorage === "undefined") return null;
  try {
    const value = JSON.parse(localStorage.getItem(OFFLINE_ACCESS_KEY) ?? "null") as Partial<OfflineAccessHint> | null;
    if (!value || !("username" in value)) return null;
    return { username: typeof value.username === "string" ? value.username : null };
  } catch {
    return null;
  }
}

export function canUseOfflineReader(): boolean {
  return navigator.onLine === false && offlineReaderAccessHint() !== null;
}
