import type {TocNode} from "../types/api";

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
