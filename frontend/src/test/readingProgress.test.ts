import { describe, expect, it } from "vitest";
import {
  clampProgressRatio,
  documentReadingPositionRatio,
  formatProgressPercent,
} from "../utils/readingProgress";

describe("reading progress presentation", () => {
  it("computes a continuous document position from the readable chapter sequence", () => {
    expect(documentReadingPositionRatio(0, 282, 0)).toBe(0);
    expect(documentReadingPositionRatio(74, 282, 0)).toBeCloseTo(74 / 282);
    expect(documentReadingPositionRatio(74, 282, 0.5)).toBeCloseTo(74.5 / 282);
    expect(documentReadingPositionRatio(281, 282, 1)).toBe(1);
  });

  it("clamps invalid ratios and never rounds incomplete progress to 100 percent", () => {
    expect(clampProgressRatio(-1)).toBe(0);
    expect(clampProgressRatio(Number.NaN)).toBe(0);
    expect(clampProgressRatio(2)).toBe(1);
    expect(formatProgressPercent(0)).toBe("0%");
    expect(formatProgressPercent(0.004)).toBe("<1%");
    expect(formatProgressPercent(0.999)).toBe("99%");
    expect(formatProgressPercent(1)).toBe("100%");
  });
});
