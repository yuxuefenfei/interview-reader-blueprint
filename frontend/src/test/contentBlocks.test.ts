import { describe, expect, it } from "vitest";
import type { ContentBlock } from "../types/api";
import { normalizeReaderBlocks } from "../utils/contentBlocks";

function block(overrides: Partial<ContentBlock>): ContentBlock {
  return {
    id: "block-1",
    blockKey: "block-1",
    seq: 1,
    blockType: "paragraph",
    payload: { text: "正文" },
    plainText: "正文",
    sourcePage: null,
    sourceBbox: null,
    confidence: null,
    ...overrides
  };
}

describe("normalizeReaderBlocks", () => {
  it("keeps a leaked closing brace and annotation inside the preceding code block", () => {
    const blocks = normalizeReaderBlocks([
      block({
        id: "code",
        blockType: "code",
        payload: { language: "java", text: "public void createOrder() {\n  saveOrder();" },
        plainText: "public void createOrder() {\n  saveOrder();"
      }),
      block({ id: "tail", seq: 2, payload: { text: "} @Transactional" }, plainText: "} @Transactional" })
    ]);

    expect(blocks).toHaveLength(1);
    expect(blocks[0].blockType).toBe("code");
    expect(blocks[0].plainText).toContain("saveOrder();\n}\n@Transactional");
  });

  it("splits prose that was fused onto a code closing brace", () => {
    const blocks = normalizeReaderBlocks([
      block({
        id: "code",
        blockType: "code",
        payload: { language: "java", text: "public void saveOrder() {" },
        plainText: "public void saveOrder() {"
      }),
      block({
        id: "mixed",
        seq: 2,
        payload: { text: "// 保存订单}在默认的代理模式下，外部调用会经过代理。" },
        plainText: "// 保存订单}在默认的代理模式下，外部调用会经过代理。"
      })
    ]);

    expect(blocks).toHaveLength(2);
    expect(blocks[0].plainText).toContain("// 保存订单}");
    expect(blocks[1].blockType).toBe("paragraph");
    expect(blocks[1].plainText).toBe("在默认的代理模式下，外部调用会经过代理。");
  });
});
