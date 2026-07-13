import { describe, expect, it } from "vitest";
import { blockAtViewportAnchor, scrollTopForBlockOffset } from "../utils/readingPosition";

describe("block reading position", () => {
  const blocks = [
    { id: "first", top: -140, bottom: -12 },
    { id: "second", top: -12, bottom: 180 },
    { id: "third", top: 180, bottom: 360 }
  ];

  it("uses the block covering the viewport anchor", () => {
    expect(blockAtViewportAnchor(blocks, 24)?.id).toBe("second");
  });

  it("falls forward when the anchor lands in a gap", () => {
    expect(blockAtViewportAnchor(blocks, 175)?.id).toBe("second");
    expect(blockAtViewportAnchor(blocks, 500)?.id).toBe("third");
  });

  it("restores a block at its saved relative viewport offset", () => {
    expect(scrollTopForBlockOffset(420, 84, 36)).toBe(468);
    expect(scrollTopForBlockOffset(120, -20, -20)).toBe(120);
    expect(scrollTopForBlockOffset(0, -80, 20)).toBe(0);
  });
});
