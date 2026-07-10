import { describe, expect, it } from "vitest";
import { pageForBbox, sourceOverlayLabel, sourceOverlayStyle } from "../utils/sourceBbox";

describe("sourceBbox utilities", () => {
  it("converts normalized bbox coordinates to overlay percentages", () => {
    expect(sourceOverlayStyle({ page: 3, x: 0.1, y: 0.2, width: 0.3, height: 0.4 })).toEqual({
      left: "10%",
      top: "20%",
      width: "30%",
      height: "40%"
    });
  });

  it("converts absolute PDF point coordinates with bounded dimensions", () => {
    expect(sourceOverlayStyle({ page: 2, x: 59.5, y: 84.2, width: 119, height: 168.4 })).toEqual({
      left: "10%",
      top: "10%",
      width: "20%",
      height: "20%"
    });
  });

  it("ignores incomplete or invalid bbox values", () => {
    expect(sourceOverlayStyle({ page: 1, x: 10, y: 10, width: 0, height: 20 })).toBeNull();
    expect(sourceOverlayStyle(null)).toBeNull();
    expect(pageForBbox({ page: 0 })).toBeNull();
  });

  it("formats the active source label", () => {
    expect(sourceOverlayLabel({ page: 5, x: 12.4, y: 20.6, width: 80.2, height: 18.8 })).toBe("p5 · x12 y21 · 80x19");
    expect(sourceOverlayLabel({ x: 12, y: 20, width: 80, height: 18 }, 7)).toBe("p7 · x12 y20 · 80x18");
  });
});
