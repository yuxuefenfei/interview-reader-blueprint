import { afterEach, describe, expect, it, vi } from "vitest";
import { createSerializedSaveQueue } from "../utils/serializedSaveQueue";

afterEach(() => {
  vi.useRealTimers();
});

describe("serialized save queue", () => {
  it("flushes the latest immutable snapshot without waiting for the debounce delay", async () => {
    vi.useFakeTimers();
    const saved: Array<{ id: string; text: string }> = [];
    const queue = createSerializedSaveQueue<{ id: string; text: string }>(700, async (snapshot) => {
      saved.push(snapshot);
      return true;
    });
    const first = { id: "block-1", text: "第一版" };
    const latest = { id: "block-1", text: "第二版" };

    queue.schedule({ ...first });
    queue.schedule({ ...latest });
    latest.text = "响应式对象之后又被修改";

    await expect(queue.flush()).resolves.toBe(true);
    expect(saved).toEqual([{ id: "block-1", text: "第二版" }]);
    expect(queue.hasWork()).toBe(false);
  });

  it("serializes saves so each task can use the revision produced by the previous task", async () => {
    let releaseFirst!: () => void;
    const firstGate = new Promise<void>((resolve) => { releaseFirst = resolve; });
    const started: number[] = [];
    const queue = createSerializedSaveQueue<number>(700, async (value) => {
      started.push(value);
      if (value === 1) await firstGate;
      return true;
    });

    const first = queue.submit(1);
    const second = queue.submit(2);
    await Promise.resolve();
    expect(started).toEqual([1]);

    releaseFirst();
    await expect(Promise.all([first, second])).resolves.toEqual([true, true]);
    expect(started).toEqual([1, 2]);
  });
});
