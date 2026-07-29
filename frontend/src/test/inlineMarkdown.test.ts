import { mount } from "@vue/test-utils";
import { describe, expect, it } from "vitest";
import InlineMarkdown from "../components/InlineMarkdown.vue";
import { parseInlineMarkdown } from "../utils/inlineMarkdown";

describe("inline Markdown", () => {
  it("parses the approved inline formatting without parsing raw HTML", () => {
    const tokens = parseInlineMarkdown("**加粗**、*斜体*、`代码`和 [文档](https://example.com) <b>原样</b>");

    expect(tokens.map((token) => token.type)).toEqual(["strong", "text", "emphasis", "text", "code", "text", "link", "text"]);
    expect(tokens.at(-1)).toMatchObject({ type: "text", text: " <b>原样</b>" });
  });

  it("renders external links safely and keeps malformed syntax as text", () => {
    const wrapper = mount(InlineMarkdown, {
      props: { text: "[外部链接](https://example.com) [危险](javascript:alert(1)) **未闭合" },
    });

    const link = wrapper.get("a");
    expect(link.attributes()).toMatchObject({ href: "https://example.com", target: "_blank", rel: "noopener noreferrer" });
    expect(wrapper.text()).toContain("[危险](javascript:alert(1)) **未闭合");
    expect(wrapper.find("script").exists()).toBe(false);
  });

  it("treats a single line break as space and an empty line as a paragraph break", () => {
    const wrapper = mount(InlineMarkdown, { props: { text: "第一行\n第二行\n\n第三段" } });

    expect(wrapper.text()).toContain("第一行 第二行第三段");
    expect(wrapper.findAll("br")).toHaveLength(2);
  });
});
