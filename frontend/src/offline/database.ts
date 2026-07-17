export const OFFLINE_DB_NAME = "interview-reader-offline";
export const OFFLINE_DB_VERSION = 3;
export const CONTENT_STORE_NAME = "node-content-cache";
export const PROGRESS_STORE_NAME = "reading-progress-queue";

export function openOfflineDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(OFFLINE_DB_NAME, OFFLINE_DB_VERSION);
    request.onupgradeneeded = (event) => createStores(
      request.result,
      request.transaction,
      (event as IDBVersionChangeEvent).oldVersion
    );
    request.onerror = () => reject(request.error);
    request.onsuccess = () => resolve(request.result);
  });
}

function createStores(db: IDBDatabase, upgrade: IDBTransaction | null, oldVersion: number): void {
  if (!db.objectStoreNames.contains(CONTENT_STORE_NAME)) {
    const store = db.createObjectStore(CONTENT_STORE_NAME, { keyPath: "cacheKey" });
    store.createIndex("updatedAt", "updatedAt");
    store.createIndex("documentId", "documentId");
  } else if (oldVersion < 3 && upgrade) {
    const store = upgrade.objectStore(CONTENT_STORE_NAME);
    store.clear();
    if (!store.indexNames.contains("documentId")) store.createIndex("documentId", "documentId");
  }
  if (!db.objectStoreNames.contains(PROGRESS_STORE_NAME)) {
    const store = db.createObjectStore(PROGRESS_STORE_NAME, { keyPath: "id" });
    store.createIndex("createdAt", "createdAt");
    store.createIndex("documentId", "documentId");
  } else if (oldVersion < 3 && upgrade) {
    const store = upgrade.objectStore(PROGRESS_STORE_NAME);
    if (!store.indexNames.contains("documentId")) store.createIndex("documentId", "documentId");
  }
}