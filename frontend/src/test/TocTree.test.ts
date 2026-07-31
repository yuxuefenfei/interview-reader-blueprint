import { mount } from "@vue/test-utils";
import { describe, expect, it } from "vitest";
import TocTree from "../components/TocTree.vue";
import type { TocNode } from "../types/api";

function node(id: string, title: string, children: TocNode[] = []): TocNode {
  return {
    id,
    parentId: null,
    title,
    level: 1,
    nodeType: children.length ? "SECTION" : "QUESTION",
    semanticRole: children.length ? null : "QUESTION",
    anchor: `anchor-${id}`,
    sourcePageStart: null,
    children,
  };
}

describe("TocTree", () => {
  it("keeps navigation and expansion as separate accessible actions", async () => {
    const child = node("child", "A very long child chapter title");
    const root = node("root", "Root chapter", [child]);
    const wrapper = mount(TocTree, {
      props: {
        nodes: [root],
        activeNodeId: child.id,
        expandedNodeIds: [root.id],
      },
    });

    const rootNavigation = wrapper.get('[data-toc-node-id="root"]');
    expect(rootNavigation.attributes("aria-label")).toBe("Root chapter");
    expect(wrapper.get('[data-toc-node-id="child"]').attributes("aria-current")).toBe("location");
    expect(wrapper.get(".toc-node-status.current").text()).toBe("当前");

    await rootNavigation.trigger("pointerenter");
    expect(wrapper.emitted("prefetch")?.[0]).toEqual([root]);

    await rootNavigation.trigger("click");
    expect(wrapper.emitted("select")?.[0]?.[0]).toEqual(root);

    const toggle = wrapper.get(".toc-toggle");
    expect(toggle.attributes("aria-expanded")).toBe("true");
    expect(toggle.get(".toc-toggle-icon").element.tagName.toLowerCase()).toBe("svg");
    await toggle.trigger("click");
    expect(wrapper.emitted("toggle")?.[0]).toEqual([root.id]);
  });

  it("caps visual indentation at three levels", () => {
    const levelFive = node("level-five", "Level five");
    const levelFour = node("level-four", "Level four", [levelFive]);
    const levelThree = node("level-three", "Level three", [levelFour]);
    const levelTwo = node("level-two", "Level two", [levelThree]);
    const root = node("root", "Root", [levelTwo]);
    const wrapper = mount(TocTree, {
      props: {
        nodes: [root],
        activeNodeId: levelFive.id,
        expandedNodeIds: [root.id, levelTwo.id, levelThree.id, levelFour.id],
      },
    });

    const deepestRow = wrapper.get('[data-toc-node-id="level-five"]').element.parentElement;
    expect(deepestRow?.getAttribute("style")).toContain("--toc-depth: 3");
    expect(deepestRow?.getAttribute("style")).toContain("--toc-indent: 48px");
  });

  it("renders parent nodes as non-selectable group labels in the compact mobile drawer", async () => {
    const child = node("child", "Child chapter");
    const root = node("root", "Part one", [child]);
    const wrapper = mount(TocTree, {
      props: {
        nodes: [root],
        activeNodeId: null,
        expandedNodeIds: [root.id],
        compactGroups: true,
      },
    });

    expect(wrapper.get(".toc-group-label").text()).toBe("Part one");
    expect(wrapper.find('[data-toc-node-id="root"]').exists()).toBe(false);
    expect(wrapper.find('[data-toc-node-id="child"]').exists()).toBe(true);

    await wrapper.get(".toc-toggle").trigger("click");
    expect(wrapper.emitted("toggle")?.[0]).toEqual([root.id]);
  });

  it("announces pending and failed chapter states", () => {
    const pending = node("pending", "Pending");
    const failed = node("failed", "Failed");
    const wrapper = mount(TocTree, {
      props: {
        nodes: [pending, failed],
        activeNodeId: null,
        expandedNodeIds: [],
        pendingNodeId: pending.id,
        failedNodeId: failed.id,
      },
    });

    expect(wrapper.get(".toc-node-status.loading").text()).toBe("加载中");
    expect(wrapper.get(".toc-node-status.failed").text()).toBe("失败，重试");
  });
});
