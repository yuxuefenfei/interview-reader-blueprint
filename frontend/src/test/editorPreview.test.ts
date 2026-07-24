import { describe, expect, it } from "vitest";
import type { EditorBlock } from "../types/api";
import { editorText, parseEditorPayload, previewBlock, previewPayload } from "../utils/editorPreview";

function block(overrides: Partial<EditorBlock> = {}): EditorBlock {
  return {
    id: "block-1",
    blockKey: "block-1",
    seq: 1,
    blockType: "paragraph",
    payload: { text: "导入内容" },
    plainText: "编辑后的正文",
    language: null,
    sourcePage: null,
    sourceBbox: null,
    confidence: null,
    ...overrides
  };
}

describe("editor preview payload", () => {
  it("uses the in-progress text for paragraph preview", () => {
    const preview = previewBlock(block(), undefined);

    expect(preview.payload).toEqual({ text: "编辑后的正文" });
  });

  it("carries the selected code language into the preview payload", () => {
    const preview = previewPayload(block({ blockType: "code", language: "sql", plainText: "SELECT 1" }), {});

    expect(preview).toMatchObject({ language: "sql", text: "SELECT 1" });
  });
  it("turns list lines into reader list items", () => {
    const preview = previewPayload(block({ blockType: "unordered_list", plainText: "第一项\n\n 第二项 " }), {});

    expect(preview.items).toEqual(["第一项", "第二项"]);
  });

  it("rebuilds a simple table preview from edited pipe-delimited text", () => {
    const preview = previewPayload(block({
      blockType: "table",
      plainText: "名称 | 值\nRedis | 快",
      payload: { columns: ["旧列"], rows: [["旧值"]] }
    }), { columns: ["旧列"], rows: [["旧值"]] });

    expect(preview).toMatchObject({ columns: ["名称", "值"], rows: [["Redis", "快"]] });
  });

  it("removes Markdown inline-code markers from edited table cells", () => {
    const preview = previewPayload(block({
      blockType: "table",
      plainText: "对象 | 说明\n`ServerSocketChannel` | 绑定 `OP_ACCEPT` 注册"
    }), {});

    expect(preview).toMatchObject({
      columns: ["对象", "说明"],
      rows: [["ServerSocketChannel", "绑定 OP_ACCEPT 注册"]]
    });
  });

  it("makes a flattened imported table editable as pipe-delimited rows", () => {
    const text = editorText(block({
      blockType: "table",
      plainText: "对象 说明 ServerSocketChannel 监听端口",
      payload: { columns: ["对象", "说明"], rows: [["ServerSocketChannel", "监听端口"]] }
    }), undefined);

    expect(text).toBe("对象 | 说明\nServerSocketChannel | 监听端口");
  });

  it("refuses malformed advanced payload without breaking the preview", () => {
    expect(parseEditorPayload("{", { text: "回退内容" })).toBeNull();
    expect(previewBlock(block(), "{").payload).toEqual({ text: "编辑后的正文" });
  });
});
