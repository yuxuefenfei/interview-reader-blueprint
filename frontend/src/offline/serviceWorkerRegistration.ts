export function registerServiceWorker(): void {
  if (!("serviceWorker" in navigator)) {
    return;
  }
  const isViteDevServer = location.port === "5173" && ["localhost", "127.0.0.1"].includes(location.hostname);
  if (isViteDevServer) {
    void navigator.serviceWorker.getRegistrations().then((registrations) => {
      return Promise.all(registrations.map((registration) => registration.unregister()));
    }).then(() => caches.keys()).then((keys) => {
      return Promise.all(keys.filter((key) => key.startsWith("interview-reader-")).map((key) => caches.delete(key)));
    });
    return;
  }
  window.addEventListener("load", () => {
    navigator.serviceWorker.register("/sw.js").catch(() => undefined);
  });
}
