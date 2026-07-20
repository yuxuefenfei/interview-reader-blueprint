export interface SerializedSaveQueue<T> {
  schedule(task: T): void;
  submit(task: T): Promise<boolean>;
  flush(): Promise<boolean>;
  cancelPending(): void;
  hasWork(): boolean;
}

/**
 * 将延迟保存串行化：定时窗口内只保留最新快照，真正执行时仍严格按提交顺序运行。
 * 调用方必须传入不可变快照，避免异步任务再次读取已经切换的响应式状态。
 */
export function createSerializedSaveQueue<T>(delayMs: number, execute: (task: T) => Promise<boolean>): SerializedSaveQueue<T> {
  let pending: T | null = null;
  let timer: number | null = null;
  let queuedCount = 0;
  let lastResult = true;
  let tail: Promise<void> = Promise.resolve();

  const enqueue = (task: T): Promise<boolean> => {
    queuedCount += 1;
    const result = tail.then(() => execute(task));
    tail = result.then(
      (value) => { lastResult = value; },
      () => { lastResult = false; }
    ).finally(() => { queuedCount -= 1; });
    return result;
  };

  const clearTimer = (): void => {
    if (timer === null) return;
    window.clearTimeout(timer);
    timer = null;
  };

  const submitPending = (): Promise<boolean> | null => {
    clearTimer();
    if (pending === null) return null;
    const task = pending;
    pending = null;
    return enqueue(task);
  };

  return {
    schedule(task) {
      pending = task;
      clearTimer();
      timer = window.setTimeout(() => { void submitPending(); }, delayMs);
    },
    submit(task) {
      return enqueue(task);
    },
    async flush() {
      const submitted = submitPending();
      if (submitted) return submitted;
      await tail;
      return lastResult;
    },
    cancelPending() {
      clearTimer();
      pending = null;
    },
    hasWork() {
      return pending !== null || queuedCount > 0;
    }
  };
}
