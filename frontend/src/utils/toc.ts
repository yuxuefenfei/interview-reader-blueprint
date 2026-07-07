import type { TocNode } from "../types/api";

export function flattenToc(nodes: TocNode[]): TocNode[] {
  return nodes.flatMap((node) => [node, ...flattenToc(node.children)]);
}

export function firstReadableNode(nodes: TocNode[]): TocNode | null {
  for (const node of flattenToc(nodes)) {
    if (node.nodeType === "QUESTION" || node.children.length === 0) {
      return node;
    }
  }
  return nodes[0] ?? null;
}

export function progressRatioForNode(nodes: TocNode[], nodeId: string): number {
  const flattened = flattenToc(nodes);
  const index = flattened.findIndex((node) => node.id === nodeId);
  if (index < 0 || flattened.length === 0) {
    return 0;
  }
  return Number(((index + 1) / flattened.length).toFixed(4));
}
