import { AxiosError } from "axios";
import { afterEach, describe, expect, it, vi } from "vitest";
import { adminApi } from "../api/admin";
import { AppError, http, normalizeHttpError } from "../api/http";
import type { ProblemDetail } from "../api/http";
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

  it("loads the most recently read document", async () => {
    const get = vi.spyOn(http, "get").mockResolvedValue({ status: 204, data: null } as never);

    await expect(readerApi.latestReadDocument()).resolves.toBeNull();

    expect(get).toHaveBeenCalledWith("/reader/reading-progress/latest-document");
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

  it("requests the next content page with the server cursor", async () => {
    const get = vi.spyOn(http, "get").mockResolvedValue({ data: { node: {}, blocks: [], nextAfterSeq: null } } as never);
    await readerApi.content("version-1", "node-1", 100);
    expect(get).toHaveBeenCalledWith("/reader/versions/version-1/nodes/node-1/content", {
      params: { afterSeq: 100, limit: 100 }
    });
  });

  it("loads the lightweight editor snapshot through the management endpoint", async () => {
    const get = vi.spyOn(http, "get").mockResolvedValue({ data: { version: {}, document: {}, nodes: [] } } as never);
    await adminApi.editor("version-1");
    expect(get).toHaveBeenCalledWith("/admin/versions/version-1/editor");
  });
  it("normalizes RFC Problem Details into an actionable AppError", () => {
    const cause = {
      message: "Request failed",
      response: {
        status: 409,
        data: {
          title: "Conflict",
          detail: "草稿版本已过期",
          code: "DRAFT_REVISION_CONFLICT",
          traceId: "12345678-1234-1234-1234-123456789abc",
          fieldErrors: { draftRevision: "must match current revision" }
        }
      }
    } as unknown as AxiosError<ProblemDetail>;

    const error = normalizeHttpError(cause);

    expect(error).toBeInstanceOf(AppError);
    expect(error.kind).toBe("conflict");
    expect(error.code).toBe("DRAFT_REVISION_CONFLICT");
    expect(error.message).toContain("追踪号：12345678");
    expect(error.fieldErrors).toEqual({ draftRevision: "must match current revision" });
    expect(error.retryable).toBe(false);
  });
});
