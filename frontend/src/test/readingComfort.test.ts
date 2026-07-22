import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  comfortStyle,
  loadReaderComfort,
  loadReaderTheme,
  persistReaderComfort,
} from "../utils/readingComfort";

describe("reader comfort preferences", () => {
  beforeEach(() => {
    const values = new Map<string, string>();
    vi.stubGlobal("localStorage", {
      clear: () => values.clear(),
      getItem: (key: string) => values.get(key) ?? null,
      removeItem: (key: string) => values.delete(key),
      setItem: (key: string, value: string) => values.set(key, value),
    });
  });

  it("uses the comfortable defaults", () => {
    expect(loadReaderComfort()).toEqual({
      fontSize: 18,
      lineHeight: 1.85,
      columnWidth: 740,
    });
    expect(loadReaderTheme()).toBe("light");
  });

  it("persists valid preferences and exposes reader CSS variables", () => {
    const comfort = { fontSize: 20, lineHeight: 1.95, columnWidth: 860 };

    persistReaderComfort(comfort);

    expect(loadReaderComfort()).toEqual(comfort);
    expect(comfortStyle(comfort)).toEqual({
      "--reader-font-size": "20px",
      "--reader-line-height": "1.95",
      "--reader-column": "860px",
    });
  });

  it("clamps corrupted numeric preferences and ignores unknown themes", () => {
    localStorage.setItem("reader.fontSize", "100");
    localStorage.setItem("reader.lineHeight", "0.5");
    localStorage.setItem("reader.columnWidth", "not-a-number");
    localStorage.setItem("reader.theme", "neon");

    expect(loadReaderComfort()).toEqual({
      fontSize: 22,
      lineHeight: 1.6,
      columnWidth: 740,
    });
    expect(loadReaderTheme()).toBe("light");
  });
});
