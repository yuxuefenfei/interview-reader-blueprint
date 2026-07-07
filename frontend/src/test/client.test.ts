import { afterEach, describe, expect, it, vi } from "vitest";
import { createNote, saveBookmark, saveReviewState } from "../api/client";

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
});
