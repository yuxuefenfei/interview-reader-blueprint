import { OFFLINE_CONTENT_CACHE_MAX_ITEMS } from "../shared/runtimePolicy";
import type { NodeContent } from "../types/api";
import { CONTENT_STORE_NAME, openOfflineDatabase } from "./database";

const FALLBACK_KEY = "reader.nodeContentCache";

let memoryFallbackCache: CachedNodeContent[] = [];

interface CachedNodeContent {
  cacheKey: string;
  documentId: string;
  versionId: string;
  nodeId: string;
  limit: number;
  content: NodeContent;
  updatedAt: string;
}

export async function cacheNodeContent(documentId: string, versionId: string, nodeId: string, limit: number, content: NodeContent): Promise<void> {
  const item: CachedNodeContent = { cacheKey: cacheKey(versionId, nodeId, limit), documentId, versionId, nodeId, limit, content, updatedAt: new Date().toISOString() };
  if (!hasIndexedDb()) {
    writeFallbackCache([item, ...readFallbackCache().filter((cached) => cached.cacheKey !== item.cacheKey)].slice(0, OFFLINE_CONTENT_CACHE_MAX_ITEMS));
    return;
  }
  const db = await openOfflineDatabase();
  try {
    await requestTransaction(db, "readwrite", (store) => store.put(item));
    await trimCache(db);
  } finally {
    db.close();
  }
}

export async function getCachedNodeContent(versionId: string, nodeId: string, limit: number): Promise<NodeContent | null> {
  if (!hasIndexedDb()) return readFallbackCache().find((item) => item.cacheKey === cacheKey(versionId, nodeId, limit))?.content ?? null;
  const db = await openOfflineDatabase();
  try {
    const item = await requestTransaction(db, "readonly", (store) => store.get(cacheKey(versionId, nodeId, limit)));
    return (item as CachedNodeContent | undefined)?.content ?? null;
  } finally {
    db.close();
  }
}

export async function purgeNodeContentForDocument(documentId: string): Promise<void> {
  if (!hasIndexedDb()) {
    writeFallbackCache(readFallbackCache().filter((item) => item.documentId && item.documentId !== documentId));
    return;
  }
  const db = await openOfflineDatabase();
  try {
    await deleteByDocument(db, documentId);
  } finally {
    db.close();
  }
}

export async function clearNodeContentCache(): Promise<void> {
  if (!hasIndexedDb()) {
    writeFallbackCache([]);
    return;
  }
  const db = await openOfflineDatabase();
  try {
    await requestTransaction(db, "readwrite", (store) => store.clear());
  } finally {
    db.close();
  }
}

function cacheKey(versionId: string, nodeId: string, limit: number): string {
  return `${versionId}:${nodeId}:${limit}`;
}

function hasIndexedDb(): boolean {
  return typeof indexedDB !== "undefined";
}

function requestTransaction<T>(
  db: IDBDatabase,
  mode: IDBTransactionMode,
  action: (store: IDBObjectStore) => IDBRequest<T>
): Promise<T> {
  return new Promise((resolve, reject) => {
    const tx = db.transaction(CONTENT_STORE_NAME, mode);
    const request = action(tx.objectStore(CONTENT_STORE_NAME));
    let result: T;
    request.onsuccess = () => { result = request.result; };
    request.onerror = () => reject(request.error);
    tx.oncomplete = () => resolve(result);
    tx.onabort = () => reject(tx.error ?? request.error);
    tx.onerror = () => reject(tx.error);
  });
}

function deleteByDocument(db: IDBDatabase, documentId: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const tx = db.transaction(CONTENT_STORE_NAME, "readwrite");
    const store = tx.objectStore(CONTENT_STORE_NAME);
    const request = store.index("documentId").openKeyCursor(IDBKeyRange.only(documentId));
    request.onsuccess = () => {
      const cursor = request.result;
      if (!cursor) return;
      store.delete(cursor.primaryKey);
      cursor.continue();
    };
    request.onerror = () => reject(request.error);
    tx.oncomplete = () => resolve();
    tx.onabort = () => reject(tx.error ?? request.error);
    tx.onerror = () => reject(tx.error);
  });
}

function trimCache(db: IDBDatabase): Promise<void> {
  return new Promise((resolve, reject) => {
    // 从最新项向后扫描，在同一个 readwrite 事务中裁剪，避免每个 key 重开事务。
    const tx = db.transaction(CONTENT_STORE_NAME, "readwrite");
    const request = tx.objectStore(CONTENT_STORE_NAME).index("updatedAt").openCursor(null, "prev");
    let retained = 0;
    request.onsuccess = () => {
      const cursor = request.result;
      if (!cursor) return;
      if (retained >= OFFLINE_CONTENT_CACHE_MAX_ITEMS) cursor.delete();
      retained += 1;
      cursor.continue();
    };
    request.onerror = () => reject(request.error);
    tx.oncomplete = () => resolve();
    tx.onabort = () => reject(tx.error ?? request.error);
    tx.onerror = () => reject(tx.error);
  });
}

function readFallbackCache(): CachedNodeContent[] {
  if (!hasLocalStorage()) return memoryFallbackCache;
  try {
    return JSON.parse(localStorage.getItem(FALLBACK_KEY) ?? "[]") as CachedNodeContent[];
  } catch {
    return [];
  }
}

function writeFallbackCache(items: CachedNodeContent[]): void {
  if (!hasLocalStorage()) {
    memoryFallbackCache = items;
    return;
  }
  localStorage.setItem(FALLBACK_KEY, JSON.stringify(items));
}

function hasLocalStorage(): boolean {
  return typeof localStorage !== "undefined";
}
