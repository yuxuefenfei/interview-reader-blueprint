import { describe, expect, it } from "vitest";
import { detachedPreviewChannelName, isDetachedPreviewMessage } from "../utils/detachedPreviewChannel";

describe("detached preview channel", () => {
  it("uses a version-scoped channel", () => {
    expect(detachedPreviewChannelName("version-a")).toBe("interview-reader:editor-preview:version-a");
    expect(detachedPreviewChannelName("version-a")).not.toBe(detachedPreviewChannelName("version-b"));
  });

  it("accepts only the supported editor preview message kinds", () => {
    expect(isDetachedPreviewMessage({ type: "preview-state-request" })).toBe(true);
    expect(isDetachedPreviewMessage({ type: "preview-state", state: {} })).toBe(true);
    expect(isDetachedPreviewMessage({ type: "other" })).toBe(false);
    expect(isDetachedPreviewMessage(null)).toBe(false);
  });
});