import type { EditorBlock } from "../types/api";

type Payload = Record<string, unknown>;

export function parseEditorPayload(serialized: string | undefined, fallback: Payload): Payload | null {
  if (serialized === undefined || serialized.trim() === "") {
    return fallback;
  }
  try {
    const parsed: unknown = JSON.parse(serialized);
    return parsed !== null && typeof parsed === "object" && !Array.isArray(parsed) ? parsed as Payload : null;
  } catch {
    return null;
  }
}

function listItems(text: string): string[] {
  return text.split(/\r?\n/).map((item) => item.trim()).filter(Boolean);
}

function tablePayload(text: string, payload: Payload): Payload {
  const lines = listItems(text);
  if (lines.length === 0) {
    return payload;
  }
  const delimiter = lines.some((line) => line.includes("|")) ? "|" : lines.some((line) => line.includes("\t")) ? "\t" : null;
  if (delimiter === null) {
    return { ...payload, text };
  }
  const rows = lines.map((line) => line.split(delimiter).map((cell) => cell.trim()).filter((cell) => cell.length > 0));
  const [columns, ...body] = rows;
  return { ...payload, columns, rows: body };
}

export function previewPayload(block: EditorBlock, payload: Payload): Payload {
  const text = block.plainText;
  switch (block.blockType) {
    case "unordered_list":
    case "ordered_list":
      return { ...payload, items: listItems(text) };
    case "table":
      return tablePayload(text, payload);
    case "formula":
      return { ...payload, latex: text };
    case "image":
      return { ...payload, alt: text };
    case "code":
      return { ...payload, text, language: block.language ?? payload.language ?? "text" };
    default:
      return { ...payload, text };
  }
}

export function previewBlock(block: EditorBlock, serializedPayload: string | undefined): EditorBlock {
  const payload = parseEditorPayload(serializedPayload, block.payload) ?? block.payload;
  return { ...block, payload: previewPayload(block, payload) };
}

export function editorTextPlaceholder(blockType: EditorBlock["blockType"]): string {
  return switchPlaceholder(blockType);
}

function switchPlaceholder(blockType: EditorBlock["blockType"]): string {
  switch (blockType) {
    case "unordered_list":
    case "ordered_list":
      return "每行一个条目…";
    case "table":
      return "表格正文；复杂表格可在扩展数据中调整…";
    case "formula":
      return "LaTeX 公式…";
    case "image":
      return "图片说明…";
    default:
      return "输入内容…";
  }
}

