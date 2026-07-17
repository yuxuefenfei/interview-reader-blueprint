export const OFFLINE_DB_NAME = "interview-reader-offline";
export const OFFLINE_DB_VERSION = 2;
export const CONTENT_STORE_NAME = "node-content-cache";
export const PROGRESS_STORE_NAME = "reading-progress-queue";

export function openOfflineDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(OFFLINE_DB_NAME, OFFLINE_DB_VERSION);
    request.onupgradeneeded = () => createStores(request.result);
    request.onerror = () => reject(request.error);
    request.onsuccess = () => resolve(request.result);
  });
}

function createStores(db: IDBDatabase): void {
  if (!db.objectStoreNames.contains(CONTENT_STORE_NAME)) {
    const store = db.createObjectStore(CONTENT_STORE_NAME, { keyPath: "cacheKey" });
    store.createIndex("updatedAt", "updatedAt");
  }
  if (!db.objectStoreNames.contains(PROGRESS_STORE_NAME)) {
    const store = db.createObjectStore(PROGRESS_STORE_NAME, { keyPath: "id" });
    store.createIndex("createdAt", "createdAt");
  }
}