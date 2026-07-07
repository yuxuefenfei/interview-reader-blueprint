import { describe, expect, it } from "vitest";
import type { TocNode } from "../types/api";
import { firstReadableNode, flattenToc, progressRatioForNode } from "../utils/toc";

const toc: TocNode[] = [
  {
    id: "root",
    parentId: null,
    title: "Root",
    level: 1,
    nodeType: "CHAPTER",
    semanticRole: null,
    anchor: "root",
    sourcePageStart: null,
    children: [
      {
        id: "question",
        parentId: "root",
        title: "Question",
        level: 2,
        nodeType: "QUESTION",
        semanticRole: "QUESTION",
        anchor: "question",
        sourcePageStart: 2,
        children: []
      }
    ]
  }
];

describe("toc utilities", () => {
  it("flattens nested toc in reading order", () => {
    expect(flattenToc(toc).map((node) => node.id)).toEqual(["root", "question"]);
  });

  it("prefers question nodes as readable entry", () => {
    expect(firstReadableNode(toc)?.id).toBe("question");
  });

  it("computes stable node progress", () => {
    expect(progressRatioForNode(toc, "question")).toBe(1);
  });
});
