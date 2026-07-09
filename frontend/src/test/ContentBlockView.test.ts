import { mount } from "@vue/test-utils";
import { describe, expect, it } from "vitest";
import ContentBlockView from "../components/ContentBlockView.vue";
import type { ContentBlock } from "../types/api";

function block(overrides: Partial<ContentBlock>): ContentBlock {
  return {
    id: "block-1",
    blockKey: "b1",
    seq: 1,
    blockType: "paragraph",
    payload: { text: "hello" },
    plainText: "hello",
    sourcePage: null,
    sourceBbox: null,
    confidence: null,
    ...overrides
  };
}

describe("ContentBlockView", () => {
  it("renders code blocks without collapsing whitespace", () => {
    const wrapper = mount(ContentBlockView, {
      props: {
        block: block({
          blockType: "code",
          payload: { language: "java", text: "class A {\n  void run() {}\n}" },
          plainText: "class A {\n  void run() {}\n}"
        })
      }
    });

    expect(wrapper.find("pre").text()).toContain("  void run()");
    expect(wrapper.text()).toContain("java");
  });

  it("renders tables with horizontal-safe markup", () => {
    const wrapper = mount(ContentBlockView, {
      props: {
        block: block({
          blockType: "table",
          payload: { columns: ["Name"], rows: [["HashMap"]] },
          plainText: "HashMap"
        })
      }
    });

    expect(wrapper.find(".table-wrap").exists()).toBe(true);
    expect(wrapper.find("td").text()).toBe("HashMap");
  });

  it("renders table snapshots without collapsing aligned text", () => {
    const wrapper = mount(ContentBlockView, {
      props: {
        block: block({
          blockType: "table_snapshot",
          payload: { text: "Topic     Risk\nHashMap   Race" },
          plainText: "Topic     Risk\nHashMap   Race",
          confidence: 0.45
        })
      }
    });

    expect(wrapper.find(".table-snapshot").exists()).toBe(true);
    expect(wrapper.find("pre").text()).toContain("HashMap   Race");
  });
});
