import { describe, expect, it } from "vitest";
import type { TocNode } from "../types/api";
import { firstReadableNode, flattenToc, isQuestionNode, progressRatioForNode, questionAnswerNodes } from "../utils/toc";

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
        children: [
          {
            id: "answer",
            parentId: "question",
            title: "Answer",
            level: 3,
            nodeType: "SECTION",
            semanticRole: "CONCLUSION",
            anchor: "answer",
            sourcePageStart: 3,
            children: []
          }
        ]
      }
    ]
  }
];

describe("toc utilities", () => {
  it("flattens nested toc in reading order", () => {
    expect(flattenToc(toc).map((node) => node.id)).toEqual(["root", "question", "answer"]);
  });

  it("prefers question nodes as readable entry", () => {
    expect(firstReadableNode(toc)?.id).toBe("question");
  });

  it("computes stable node progress", () => {
    expect(progressRatioForNode(toc, "question")).toBe(0.6667);
  });

  it("detects question nodes and answer descendants for folded review mode", () => {
    const question = toc[0].children[0];

    expect(isQuestionNode(question)).toBe(true);
    expect(questionAnswerNodes(question).map((node) => node.id)).toEqual(["answer"]);
  });
});
