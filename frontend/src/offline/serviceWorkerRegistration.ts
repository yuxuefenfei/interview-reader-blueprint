import { FRONTEND_DEV_PORT, LOCAL_DEV_HOSTS } from "../shared/runtimeConfig";

export const SERVICE_WORKER_UPDATE_EVENT = "interview-reader-update-available";
let waitingWorker: ServiceWorker | null = null;
let reloading = false;

export function registerServiceWorker(): void {
  if (!("serviceWorker" in navigator)) return;
  const isViteDevServer = location.port === String(FRONTEND_DEV_PORT)
    && LOCAL_DEV_HOSTS.some((host) => host === location.hostname);
  if (isViteDevServer) {
    void navigator.serviceWorker.getRegistrations().then((registrations) => {
      return Promise.all(registrations.map((registration) => registration.unregister()));
    }).then(() => caches.keys()).then((keys) => {
      return Promise.all(keys.filter((key) => key.startsWith("interview-reader-")).map((key) => caches.delete(key)));
    });
    return;
  }
  navigator.serviceWorker.addEventListener("controllerchange", () => {
    if (reloading) return;
    reloading = true;
    window.location.reload();
  });
  window.addEventListener("load", () => {
    void navigator.serviceWorker.register(`/sw.js?build=${encodeURIComponent(__APP_BUILD_ID__)}`, { updateViaCache: "none" })
      .then((registration) => {
        if (registration.waiting) notifyUpdate(registration.waiting);
        registration.addEventListener("updatefound", () => {
          const worker = registration.installing;
          if (!worker) return;
          worker.addEventListener("statechange", () => {
            if (worker.state === "installed" && navigator.serviceWorker.controller) notifyUpdate(worker);
          });
        });
      })
      .catch(() => undefined);
  });
}

export function activateServiceWorkerUpdate(): void {
  waitingWorker?.postMessage({ type: "SKIP_WAITING" });
}

function notifyUpdate(worker: ServiceWorker): void {
  waitingWorker = worker;
  window.dispatchEvent(new Event(SERVICE_WORKER_UPDATE_EVENT));
}
