import { describe, expect, it, vi } from "vitest";
import { getOrCreateReadingDeviceId } from "../utils/readingDevice";

describe("reading device id", () => {
  it("creates and persists one stable id", () => {
    const values = new Map<string, string>();
    const storage = {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => values.set(key, value)
    };
    const createId = vi.fn(() => "device-1");

    expect(getOrCreateReadingDeviceId(storage, createId)).toBe("device-1");
    expect(getOrCreateReadingDeviceId(storage, createId)).toBe("device-1");
    expect(createId).toHaveBeenCalledOnce();
  });
});
