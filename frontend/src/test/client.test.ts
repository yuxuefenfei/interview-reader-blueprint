import { afterEach, describe, expect, it, vi } from "vitest";
import { adminApi } from "../api/admin";
import { http } from "../api/http";
import { readerApi } from "../api/reader";

describe("Axios API domains", () => {
  afterEach(() => vi.restoreAllMocks());

  it("keeps reader requests in the reader API domain", async () => {
    const get = vi.spyOn(http, "get").mockResolvedValue({ data: { items: [], nextCursor: null } } as never);
    await readerApi.documents("Java", "cursor-1", 16);
    expect(get).toHaveBeenCalledWith("/reader/documents", {
      params: { query: "Java", cursor: "cursor-1", limit: 16 }
    });
  });

  it("uses Axios multipart uploads and lets the server recognize source type", async () => {
    const post = vi.spyOn(http, "post").mockResolvedValue({ data: { id: "job-1" } } as never);
    const file = new File(["pdf"], "sample.pdf", { type: "application/pdf" });
    await adminApi.upload(file, "document-1");
    expect(post).toHaveBeenCalledWith("/admin/import-jobs", expect.any(FormData));
    const body = post.mock.calls[0][1] as FormData;
    expect(body.get("sourceType")).toBeNull();
    expect(body.get("targetDocumentId")).toBe("document-1");
  });

  it("loads the lightweight editor snapshot through the management endpoint", async () => {
    const get = vi.spyOn(http, "get").mockResolvedValue({ data: { version: {}, document: {}, nodes: [] } } as never);
    await adminApi.editor("version-1");
    expect(get).toHaveBeenCalledWith("/admin/versions/version-1/editor");
  });
});