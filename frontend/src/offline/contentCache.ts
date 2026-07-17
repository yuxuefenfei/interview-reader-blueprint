import type { NodeContent } from "../types/api";
import { CONTENT_STORE_NAME, openOfflineDatabase } from "./database";

const FALLBACK_KEY = "reader.nodeContentCache";
const MAX_CACHE_ITEMS = 30;
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
    writeFallbackCache([item, ...readFallbackCache().filter((cached) => cached.cacheKey !== item.cacheKey)].slice(0, MAX_CACHE_ITEMS));
    return;
  }
  const db = await openOfflineDatabase();
  try {
    await transaction(db, "readwrite", (store) => store.put(item));
    await trimCache(db);
  } finally { db.close(); }
}

export async function getCachedNodeContent(versionId: string, nodeId: string, limit: number): Promise<NodeContent | null> {
  if (!hasIndexedDb()) return readFallbackCache().find((item) => item.cacheKey === cacheKey(versionId, nodeId, limit))?.content ?? null;
  const db = await openOfflineDatabase();
  try {
    const item = await transaction(db, "readonly", (store) => store.get(cacheKey(versionId, nodeId, limit)));
    return (item as CachedNodeContent | undefined)?.content ?? null;
  } finally { db.close(); }
}

export async function purgeNodeContentForDocument(documentId: string): Promise<void> {
  if (!hasIndexedDb()) {
    writeFallbackCache(readFallbackCache().filter((item) => item.documentId && item.documentId !== documentId));
    return;
  }
  const db = await openOfflineDatabase();
  try {
    const keys = await transaction(db, "readonly", (store) => store.index("documentId").getAllKeys(documentId));
    for (const key of keys) await transaction(db, "readwrite", (store) => store.delete(key));
  } finally { db.close(); }
}

export async function clearNodeContentCache(): Promise<void> {
  if (!hasIndexedDb()) { writeFallbackCache([]); return; }
  const db = await openOfflineDatabase();
  try { await transaction(db, "readwrite", (store) => store.clear()); } finally { db.close(); }
}

function cacheKey(versionId: string, nodeId: string, limit: number): string { return `${versionId}:${nodeId}:${limit}`; }
function hasIndexedDb(): boolean { return typeof indexedDB !== "undefined"; }
function transaction<T>(db: IDBDatabase, mode: IDBTransactionMode, action: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    const tx = db.transaction(CONTENT_STORE_NAME, mode);
    const request = action(tx.objectStore(CONTENT_STORE_NAME));
    request.onerror = () => reject(request.error);
    request.onsuccess = () => resolve(request.result);
    tx.onerror = () => reject(tx.error);
  });
}
async function trimCache(db: IDBDatabase): Promise<void> {
  const items = await transaction(db, "readonly", (store) => store.index("updatedAt").getAll());
  const staleItems = (items as CachedNodeContent[]).sort((left, right) => right.updatedAt.localeCompare(left.updatedAt)).slice(MAX_CACHE_ITEMS);
  for (const item of staleItems) await transaction(db, "readwrite", (store) => store.delete(item.cacheKey));
}
function readFallbackCache(): CachedNodeContent[] {
  if (!hasLocalStorage()) return memoryFallbackCache;
  try { return JSON.parse(localStorage.getItem(FALLBACK_KEY) ?? "[]") as CachedNodeContent[]; } catch { return []; }
}
function writeFallbackCache(items: CachedNodeContent[]): void {
  if (!hasLocalStorage()) { memoryFallbackCache = items; return; }
  localStorage.setItem(FALLBACK_KEY, JSON.stringify(items));
}
function hasLocalStorage(): boolean { return typeof localStorage !== "undefined"; }