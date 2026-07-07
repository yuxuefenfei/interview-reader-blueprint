export function registerServiceWorker(): void {
  if (!("serviceWorker" in navigator) || location.hostname === "localhost" && location.port === "5173") {
    return;
  }
  window.addEventListener("load", () => {
    navigator.serviceWorker.register("/sw.js").catch(() => undefined);
  });
}
