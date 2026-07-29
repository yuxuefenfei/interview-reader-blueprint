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
  it("renders restricted inline Markdown in reader and editor previews", () => {
    const wrapper = mount(ContentBlockView, {
      props: {
        block: block({
          payload: { text: "**误区一**：*不是* `Schema` [参考](https://example.com)" },
          plainText: "**误区一**：*不是* `Schema` [参考](https://example.com)",
        }),
      },
    });

    expect(wrapper.get("strong").text()).toBe("误区一");
    expect(wrapper.get("em").text()).toBe("不是");
    expect(wrapper.get("code").text()).toBe("Schema");
    expect(wrapper.get("a").attributes("target")).toBe("_blank");
  });

  it("renders code blocks without collapsing whitespace unless the reading setting enables wrapping", async () => {
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
    expect(wrapper.find(".code-copy").exists()).toBe(true);
    expect(wrapper.get("pre").attributes("style")).toBeUndefined();
    await wrapper.setProps({ wrapCode: true });
    expect(wrapper.get("pre").attributes("style")).toContain("white-space: pre-wrap");
    await wrapper.setProps({ showCodeWrapToggle: true });
    await wrapper.get('[aria-label="关闭代码自动换行"]').trigger("click");
    expect(wrapper.emitted("update:wrapCode")).toEqual([[false]]);
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

  it("renders imported Markdown table cells without inline-code backticks", () => {
    const wrapper = mount(ContentBlockView, {
      props: {
        block: block({
          blockType: "table",
          payload: { columns: ["对象"], rows: [["`ServerSocketChannel`", "绑定 `OP_ACCEPT` 注册"]] },
          plainText: "对象 | 说明\n`ServerSocketChannel` | 绑定 `OP_ACCEPT` 注册"
        })
      }
    });

    expect(wrapper.text()).toContain("ServerSocketChannel");
    expect(wrapper.text()).toContain("绑定 OP_ACCEPT 注册");
    expect(wrapper.text()).not.toContain("`");
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

  it("retries an image after the editor broadcasts a new asset key", async () => {
    const wrapper = mount(ContentBlockView, {
      props: {
        assetBaseUrl: "/api/admin/versions/v1/editor/assets",
        block: block({ blockType: "image", payload: { assetKey: "old-image", alt: "旧图片" }, plainText: "旧图片" })
      }
    });

    await wrapper.get("img").trigger("error");
    expect(wrapper.find("img").exists()).toBe(false);

    await wrapper.setProps({
      block: block({ blockType: "image", payload: { assetKey: "new-image", alt: "新图片" }, plainText: "新图片" })
    });
    expect(wrapper.get("img").attributes("src")).toBe("/api/admin/versions/v1/editor/assets/new-image");
  });

  it("lazy-loads images and restores focus after closing the keyboard-accessible preview", async () => {
    const wrapper = mount(ContentBlockView, {
      attachTo: document.body,
      props: {
        block: block({ blockType: "image", payload: { src: "/diagram.png", alt: "架构图" }, plainText: "架构图" })
      }
    });
    const trigger = wrapper.get('[aria-label="查看大图：架构图"]');

    expect(trigger.get("img").attributes("loading")).toBe("lazy");
    await trigger.trigger("click");
    expect(document.querySelector('[role="dialog"]')).not.toBeNull();
    expect(document.activeElement?.getAttribute("aria-label")).toBe("关闭图片预览");

    document.dispatchEvent(new KeyboardEvent("keydown", { key: "Tab" }));
    await wrapper.vm.$nextTick();
    expect(document.activeElement?.getAttribute("aria-label")).toBe("放大图片");
    document.dispatchEvent(new KeyboardEvent("keydown", { key: "Tab", shiftKey: true }));
    await wrapper.vm.$nextTick();
    expect(document.activeElement?.getAttribute("aria-label")).toBe("关闭图片预览");

    document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" }));
    await wrapper.vm.$nextTick();
    expect(document.querySelector('[role="dialog"]')).toBeNull();
    expect(document.activeElement).toBe(trigger.element);
    wrapper.unmount();
  });
});
