import { AppError } from "../api/http";
import type { ReadingProgress } from "../types/api";
import { openOfflineDatabase, PROGRESS_STORE_NAME } from "./database";

const FALLBACK_KEY = "reader.offlineProgressQueue";
let memoryFallbackQueue: QueuedProgress[] = [];
let activeFlush: Promise<number> | null = null;
let fallbackMutation: Promise<void> = Promise.resolve();

interface QueuedProgress {
  id: number;
  documentId: string;
  progress: ReadingProgress;
  createdAt: string;
}

type SendProgress = (documentId: string, progress: ReadingProgress) => Promise<ReadingProgress>;

export function shouldQueueReadingProgress(error: unknown): boolean {
  return error instanceof AppError && error.retryable;
}

export async function enqueueReadingProgress(documentId: string, progress: ReadingProgress): Promise<void> {
  const item: QueuedProgress = {
    id: Date.now() + Math.floor(Math.random() * 1000),
    documentId,
    progress,
    createdAt: new Date().toISOString()
  };
  if (!hasIndexedDb()) {
    await withFallbackLock(() => writeFallbackQueue([...readFallbackQueue(), item]));
    return;
  }
  const db = await openOfflineDatabase();
  try {
    await requestTransaction(db, "readwrite", (store) => store.add(item));
  } finally {
    db.close();
  }
}

export function flushReadingProgressQueue(send: SendProgress): Promise<number> {
  if (activeFlush) return activeFlush;
  // online 事件、页面挂载和手动刷新可能同时触发；共享同一个 Promise 可避免重复发送。
  activeFlush = performFlush(send).finally(() => {
    activeFlush = null;
  });
  return activeFlush;
}

async function performFlush(send: SendProgress): Promise<number> {
  if (!hasIndexedDb()) {
    return withFallbackLock(() => flushFallbackQueue(send));
  }
  const db = await openOfflineDatabase();
  try {
    const items = await listQueuedProgress(db);
    let sent = 0;
    let firstDeleteError: unknown = null;
    for (const item of items) {
      await send(item.documentId, item.progress);
      try {
        // 网络确认与本地删除无法组成原子事务；服务端按客户端时间幂等，删除失败时允许下次安全重放。
        await requestTransaction(db, "readwrite", (store) => store.delete(item.id));
      } catch (error) {
        firstDeleteError ??= error;
      }
      sent += 1;
    }
    if (firstDeleteError) throw firstDeleteError;
    return sent;
  } finally {
    db.close();
  }
}

export async function purgeReadingProgressForDocument(documentId: string): Promise<void> {
  if (!hasIndexedDb()) {
    await withFallbackLock(() => writeFallbackQueue(readFallbackQueue().filter((item) => item.documentId !== documentId)));
    return;
  }
  const db = await openOfflineDatabase();
  try {
    await deleteByIndex(db, "documentId", documentId);
  } finally {
    db.close();
  }
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
    const tx = db.transaction(PROGRESS_STORE_NAME, mode);
    const request = action(tx.objectStore(PROGRESS_STORE_NAME));
    let result: T;
    request.onsuccess = () => { result = request.result; };
    request.onerror = () => reject(request.error);
    tx.oncomplete = () => resolve(result);
    tx.onabort = () => reject(tx.error ?? request.error);
    tx.onerror = () => reject(tx.error);
  });
}

function deleteByIndex(db: IDBDatabase, indexName: string, value: IDBValidKey): Promise<void> {
  return new Promise((resolve, reject) => {
    const tx = db.transaction(PROGRESS_STORE_NAME, "readwrite");
    const request = tx.objectStore(PROGRESS_STORE_NAME).index(indexName).openKeyCursor(IDBKeyRange.only(value));
    request.onsuccess = () => {
      const cursor = request.result;
      if (!cursor) return;
      tx.objectStore(PROGRESS_STORE_NAME).delete(cursor.primaryKey);
      cursor.continue();
    };
    request.onerror = () => reject(request.error);
    tx.oncomplete = () => resolve();
    tx.onabort = () => reject(tx.error ?? request.error);
    tx.onerror = () => reject(tx.error);
  });
}

function listQueuedProgress(db: IDBDatabase): Promise<QueuedProgress[]> {
  return requestTransaction(db, "readonly", (store) => store.index("createdAt").getAll())
    .then((items) => items as QueuedProgress[]);
}

async function flushFallbackQueue(send: SendProgress): Promise<number> {
  const queue = readFallbackQueue();
  let sent = 0;
  for (const item of queue) {
    await send(item.documentId, item.progress);
    sent += 1;
    writeFallbackQueue(queue.slice(sent));
  }
  return sent;
}

function withFallbackLock<T>(action: () => T | Promise<T>): Promise<T> {
  const next = fallbackMutation.then(action, action);
  fallbackMutation = next.then(() => undefined, () => undefined);
  return next;
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
