import type { ReadingProgress } from "../types/api";
import { openOfflineDatabase, PROGRESS_STORE_NAME } from "./database";





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
  const db = await openOfflineDatabase();
  await transaction(db, "readwrite", (store) => store.add(item));
  db.close();
}

export async function flushReadingProgressQueue(send: SendProgress): Promise<number> {
  if (!hasIndexedDb()) {
    return flushFallbackQueue(send);
  }
  const db = await openOfflineDatabase();
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


function transaction<T>(db: IDBDatabase, mode: IDBTransactionMode, action: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    const tx = db.transaction(PROGRESS_STORE_NAME, mode);
    const request = action(tx.objectStore(PROGRESS_STORE_NAME));
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
