import type { ReadingProgress } from "../types/api";

const DB_NAME = "interview-reader-offline";
const DB_VERSION = 2;
const STORE_NAME = "reading-progress-queue";
const CONTENT_STORE_NAME = "node-content-cache";
const FALLBACK_KEY = "reader.offlineProgressQueue";
var memoryFallbackQueue: QueuedProgress[] = [];

interface QueuedProgress {
  id: number;
  documentId: string;
  progress: ReadingProgress;
  createdAt: string;
}

type SendProgress = (documentId: string, progress: ReadingProgress) => Promise<ReadingProgress>;

export async function enqueueReadingProgress(documentId: string, progress: ReadingProgress): Promise<void> {
  const item: QueuedProgress = {
    id: Date.now() + Math.floor(Math.random() * 1000),
    documentId,
    progress,
    createdAt: new Date().toISOString()
  };
  if (!hasIndexedDb()) {
    writeFallbackQueue([...readFallbackQueue(), item]);
    return;
  }
  const db = await openDb();
  await transaction(db, "readwrite", (store) => store.add(item));
  db.close();
}

export async function flushReadingProgressQueue(send: SendProgress): Promise<number> {
  if (!hasIndexedDb()) {
    return flushFallbackQueue(send);
  }
  const db = await openDb();
  try {
    const items = await listQueuedProgress(db);
    var sent = 0;
    for (const item of items) {
      await send(item.documentId, item.progress);
      await transaction(db, "readwrite", (store) => store.delete(item.id));
      sent += 1;
    }
    return sent;
  } finally {
    db.close();
  }
}

function hasIndexedDb(): boolean {
  return typeof indexedDB !== "undefined";
}

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(CONTENT_STORE_NAME)) {
        const store = db.createObjectStore(CONTENT_STORE_NAME, { keyPath: "cacheKey" });
        store.createIndex("updatedAt", "updatedAt");
      }
      if (!db.objectStoreNames.contains(STORE_NAME)) {
        const store = db.createObjectStore(STORE_NAME, { keyPath: "id" });
        store.createIndex("createdAt", "createdAt");
      }
    };
    request.onerror = () => reject(request.error);
    request.onsuccess = () => resolve(request.result);
  });
}

function transaction<T>(db: IDBDatabase, mode: IDBTransactionMode, action: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_NAME, mode);
    const request = action(tx.objectStore(STORE_NAME));
    request.onerror = () => reject(request.error);
    request.onsuccess = () => resolve(request.result);
    tx.onerror = () => reject(tx.error);
  });
}

function listQueuedProgress(db: IDBDatabase): Promise<QueuedProgress[]> {
  return transaction(db, "readonly", (store) => store.index("createdAt").getAll()).then((items) => items as QueuedProgress[]);
}

async function flushFallbackQueue(send: SendProgress): Promise<number> {
  const queue = readFallbackQueue();
  var sent = 0;
  for (const item of queue) {
    await send(item.documentId, item.progress);
    sent += 1;
    writeFallbackQueue(queue.slice(sent));
  }
  return sent;
}

function readFallbackQueue(): QueuedProgress[] {
  if (!hasLocalStorage()) {
    return memoryFallbackQueue;
  }
  try {
    return JSON.parse(localStorage.getItem(FALLBACK_KEY) ?? "[]") as QueuedProgress[];
  } catch {
    return [];
  }
}

function writeFallbackQueue(queue: QueuedProgress[]): void {
  if (!hasLocalStorage()) {
    memoryFallbackQueue = queue;
    return;
  }
  localStorage.setItem(FALLBACK_KEY, JSON.stringify(queue));
}

function hasLocalStorage(): boolean {
  return typeof localStorage !== "undefined";
}
