import type { EditorBlock, EditorDocument, EditorNode } from "../types/api";

export interface DetachedPreviewState {
  versionId: string;
  document: Pick<EditorDocument, "title">;
  node: EditorNode;
  blocks: EditorBlock[];
  activeBlockId: string | null;
  updatedAt: number;
}

export type DetachedPreviewMessage =
  | { type: "preview-state"; state: DetachedPreviewState }
  | { type: "preview-state-request" }
  | { type: "preview-close" };

export function detachedPreviewChannelName(versionId: string): string {
  return `interview-reader:editor-preview:${versionId}`;
}

export function isDetachedPreviewMessage(value: unknown): value is DetachedPreviewMessage {
  if (!value || typeof value !== "object" || !("type" in value)) return false;
  return value.type === "preview-state" || value.type === "preview-state-request" || value.type === "preview-close";
}