import type { DocumentSummary, TocNode } from "../types/api";
import { BOOTSTRAP_STORE_NAME, openOfflineDatabase } from "./database";

const FALLBACK_KEY = "reader.bootstrapCache.v1";
const DOCUMENTS_KEY = "documents";

interface BootstrapCacheItem {
  cacheKey: string;
  documentId: string;
  documents?: DocumentSummary[];
  toc?: TocNode[];
  updatedAt: string;
}

export async function cacheReaderDocuments(documents: DocumentSummary[]): Promise<void> {
  await putItem({
    cacheKey: DOCUMENTS_KEY,
    documentId: "",
    documents,
    updatedAt: new Date().toISOString(),
  });
}

export async function getCachedReaderDocuments(): Promise<DocumentSummary[]> {
  return (await getItem(DOCUMENTS_KEY))?.documents ?? [];
}

export async function cacheReaderToc(documentId: string, versionId: string, toc: TocNode[]): Promise<void> {
  await putItem({
    cacheKey: tocKey(versionId),
    documentId,
    toc,
    updatedAt: new Date().toISOString(),
  });
}

export async function getCachedReaderToc(versionId: string): Promise<TocNode[] | null> {
  return (await getItem(tocKey(versionId)))?.toc ?? null;
}

export async function purgeReaderBootstrapForDocument(documentId: string): Promise<void> {
  if (!hasIndexedDb()) {
    const items = readFallbackItems();
    const documents = items[DOCUMENTS_KEY]?.documents?.filter((document) => document.id !== documentId) ?? [];
    writeFallbackItems({
      ...Object.fromEntries(Object.entries(items).filter(([, item]) => item.documentId !== documentId)),
      [DOCUMENTS_KEY]: {
        cacheKey: DOCUMENTS_KEY,
        documentId: "",
        documents,
        updatedAt: new Date().toISOString(),
      },
    });
    return;
  }
  const db = await openOfflineDatabase();
  try {
    const documentsItem = await transaction<BootstrapCacheItem | undefined>(db, "readonly", (store) => store.get(DOCUMENTS_KEY));
    if (documentsItem?.documents) {
      const cachedDocuments = documentsItem.documents;
      await transaction(db, "readwrite", (store) => store.put({
        ...documentsItem,
        documents: cachedDocuments.filter((document) => document.id !== documentId),
        updatedAt: new Date().toISOString(),
      }));
    }
    await deleteByDocument(db, documentId);
  } finally {
    db.close();
  }
}

async function getItem(cacheKey: string): Promise<BootstrapCacheItem | null> {
  if (!hasIndexedDb()) return readFallbackItems()[cacheKey] ?? null;
  const db = await openOfflineDatabase();
  try {
    return await transaction<BootstrapCacheItem | undefined>(db, "readonly", (store) => store.get(cacheKey)) ?? null;
  } finally {
    db.close();
  }
}

async function putItem(item: BootstrapCacheItem): Promise<void> {
  if (!hasIndexedDb()) {
    writeFallbackItems({ ...readFallbackItems(), [item.cacheKey]: item });
    return;
  }
  const db = await openOfflineDatabase();
  try {
    await transaction(db, "readwrite", (store) => store.put(item));
  } finally {
    db.close();
  }
}

function transaction<T>(
  db: IDBDatabase,
  mode: IDBTransactionMode,
  action: (store: IDBObjectStore) => IDBRequest<T>,
): Promise<T> {
  return new Promise((resolve, reject) => {
    const tx = db.transaction(BOOTSTRAP_STORE_NAME, mode);
    const request = action(tx.objectStore(BOOTSTRAP_STORE_NAME));
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
    const tx = db.transaction(BOOTSTRAP_STORE_NAME, "readwrite");
    const request = tx.objectStore(BOOTSTRAP_STORE_NAME).index("documentId").openKeyCursor(IDBKeyRange.only(documentId));
    request.onsuccess = () => {
      const cursor = request.result;
      if (!cursor) return;
      cursor.delete();
      cursor.continue();
    };
    request.onerror = () => reject(request.error);
    tx.oncomplete = () => resolve();
    tx.onabort = () => reject(tx.error ?? request.error);
    tx.onerror = () => reject(tx.error);
  });
}

function tocKey(versionId: string): string {
  return `toc:${versionId}`;
}

function hasIndexedDb(): boolean {
  return typeof indexedDB !== "undefined";
}

function readFallbackItems(): Record<string, BootstrapCacheItem> {
  if (typeof localStorage === "undefined") return {};
  try {
    return JSON.parse(localStorage.getItem(FALLBACK_KEY) ?? "{}") as Record<string, BootstrapCacheItem>;
  } catch {
    return {};
  }
}

function writeFallbackItems(items: Record<string, BootstrapCacheItem>): void {
  if (typeof localStorage !== "undefined") localStorage.setItem(FALLBACK_KEY, JSON.stringify(items));
}
