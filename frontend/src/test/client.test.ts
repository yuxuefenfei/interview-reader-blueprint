import { afterEach, describe, expect, it, vi } from "vitest";
import {
  cancelImportJob,
  createNote,
  getImportIssues,
  getNormalizedPackage,
  getReviewQueue,
  reviseStagedBlock,
  reviseStagedSection,
  saveBookmark,
  saveReviewState,
  searchContent,
  sourceFileUrl
} from "../api/client";

function mockJsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" }
  });
}

describe("api client interactions", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("posts bookmarks to the backend contract", async () => {
    const fetch = vi.fn().mockResolvedValue(mockJsonResponse({ id: "bookmark-1" }));
    vi.stubGlobal("fetch", fetch);

    await saveBookmark({
      documentId: "document-1",
      versionId: "version-1",
      sectionId: "section-1",
      blockId: "block-1",
      title: "Important"
    });

    expect(fetch).toHaveBeenCalledWith(
      "/api/bookmarks",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({
          documentId: "document-1",
          versionId: "version-1",
          sectionId: "section-1",
          blockId: "block-1",
          title: "Important"
        })
      })
    );
  });

  it("posts notes with block context", async () => {
    const fetch = vi.fn().mockResolvedValue(mockJsonResponse({ id: "note-1" }));
    vi.stubGlobal("fetch", fetch);

    await createNote({
      documentId: "document-1",
      versionId: "version-1",
      sectionId: "section-1",
      blockId: "block-1",
      selectedText: "HashMap",
      body: "Review concurrent writes"
    });

    expect(fetch).toHaveBeenCalledWith(
      "/api/notes",
      expect.objectContaining({
        method: "POST",
        body: expect.stringContaining("Review concurrent writes")
      })
    );
  });

  it("upserts review state for a toc node", async () => {
    const fetch = vi.fn().mockResolvedValue(mockJsonResponse({ mastery: "KNOWN" }));
    vi.stubGlobal("fetch", fetch);

    await saveReviewState("node-1", "document-1", "KNOWN");

    expect(fetch).toHaveBeenCalledWith(
      "/api/review-states/node-1",
      expect.objectContaining({
        method: "PUT",
        body: JSON.stringify({ documentId: "document-1", mastery: "KNOWN" })
      })
    );
  });

  it("gets the review queue with document and due filters", async () => {
    const fetch = vi.fn().mockResolvedValue(mockJsonResponse([]));
    vi.stubGlobal("fetch", fetch);

    await getReviewQueue("document-1", 5, true);

    expect(fetch).toHaveBeenCalledWith("/api/review-queue?documentId=document-1&limit=5&dueOnly=true", undefined);
  });

  it("searches content with encoded query and optional document scope", async () => {
    const fetch = vi.fn().mockResolvedValue(mockJsonResponse([]));
    vi.stubGlobal("fetch", fetch);

    await searchContent("HashMap 并发", "document-1", 8);

    expect(fetch).toHaveBeenCalledWith(
      "/api/search?q=HashMap+%E5%B9%B6%E5%8F%91&limit=8&documentId=document-1",
      undefined
    );
  });

  it("gets import review artifacts", async () => {
    const fetch = vi.fn().mockImplementation(() => Promise.resolve(mockJsonResponse([])));
    vi.stubGlobal("fetch", fetch);

    await getImportIssues("job-1");
    await getNormalizedPackage("job-1");

    expect(fetch).toHaveBeenNthCalledWith(1, "/api/import-jobs/job-1/issues", undefined);
    expect(fetch).toHaveBeenNthCalledWith(2, "/api/import-jobs/job-1/normalized-package", undefined);
  });

  it("builds source file review URLs", () => {
    expect(sourceFileUrl("job-1")).toBe("/api/import-jobs/job-1/source-file");
    expect(sourceFileUrl("job-1", 7)).toBe("/api/import-jobs/job-1/source-file#page=7");
  });

  it("cancels active import jobs", async () => {
    const fetch = vi.fn().mockResolvedValue(mockJsonResponse({ id: "job-1", status: "CANCELED" }));
    vi.stubGlobal("fetch", fetch);

    await cancelImportJob("job-1");

    expect(fetch).toHaveBeenCalledWith(
      "/api/import-jobs/job-1/cancel",
      expect.objectContaining({ method: "POST" })
    );
  });

  it("patches staged sections and blocks before commit", async () => {
    const fetch = vi.fn().mockImplementation(() => Promise.resolve(mockJsonResponse({ sections: [], blocks: [] })));
    vi.stubGlobal("fetch", fetch);

    await reviseStagedSection("job-1", "q1", { title: "Edited" });
    await reviseStagedBlock("job-1", "b1", { plainText: "Edited text" });

    expect(fetch).toHaveBeenNthCalledWith(
      1,
      "/api/import-jobs/job-1/normalized-package/sections/q1",
      expect.objectContaining({ method: "PATCH", body: JSON.stringify({ title: "Edited" }) })
    );
    expect(fetch).toHaveBeenNthCalledWith(
      2,
      "/api/import-jobs/job-1/normalized-package/blocks/b1",
      expect.objectContaining({ method: "PATCH", body: JSON.stringify({ plainText: "Edited text" }) })
    );
  });
});
