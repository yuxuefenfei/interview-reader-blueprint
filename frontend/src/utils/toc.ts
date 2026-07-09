import type { TocNode } from "../types/api";

export function flattenToc(nodes: TocNode[]): TocNode[] {
  return nodes.flatMap((node) => [node, ...flattenToc(node.children)]);
}

export function firstReadableNode(nodes: TocNode[]): TocNode | null {
  for (const node of flattenToc(nodes)) {
    if (isQuestionNode(node) || node.children.length === 0) {
      return node;
    }
  }
  return nodes[0] ?? null;
}

export function isQuestionNode(node: TocNode | null): boolean {
  return node?.nodeType === "QUESTION" || node?.semanticRole === "QUESTION";
}

export function questionAnswerNodes(node: TocNode | null): TocNode[] {
  if (!node || !isQuestionNode(node)) {
    return [];
  }
  return flattenToc(node.children).filter((candidate) => !isQuestionNode(candidate));
}

export function progressRatioForNode(nodes: TocNode[], nodeId: string): number {
  const flattened = flattenToc(nodes);
  const index = flattened.findIndex((node) => node.id === nodeId);
  if (index < 0 || flattened.length === 0) {
    return 0;
  }
  return Number(((index + 1) / flattened.length).toFixed(4));
}
