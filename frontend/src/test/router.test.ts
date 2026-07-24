import { afterEach, describe, expect, it, vi } from "vitest";
import { ADMIN_MOBILE_MEDIA_QUERY } from "../shared/responsive";

type MediaChangeListener = (event: MediaQueryListEvent) => void;

const originalMatchMedia = window.matchMedia;

function installMatchMedia(initialMatches: boolean): { setMatches: (matches: boolean) => void } {
  let matches = initialMatches;
  const listeners = new Set<MediaChangeListener>();
  const mediaQueryList = {
    get matches() { return matches; },
    media: ADMIN_MOBILE_MEDIA_QUERY,
    onchange: null,
    addEventListener: vi.fn((type: string, listener: MediaChangeListener) => {
      if (type === "change") listeners.add(listener);
    }),
    removeEventListener: vi.fn((type: string, listener: MediaChangeListener) => {
      if (type === "change") listeners.delete(listener);
    }),
    addListener: vi.fn((listener: MediaChangeListener) => listeners.add(listener)),
    removeListener: vi.fn((listener: MediaChangeListener) => listeners.delete(listener)),
    dispatchEvent: vi.fn()
  } as unknown as MediaQueryList;

  Object.defineProperty(window, "matchMedia", {
    configurable: true,
    writable: true,
    value: vi.fn(() => mediaQueryList)
  });

  return {
    setMatches(value: boolean) {
      matches = value;
      const event = { matches: value, media: ADMIN_MOBILE_MEDIA_QUERY } as MediaQueryListEvent;
      listeners.forEach((listener) => listener(event));
    }
  };
}

async function loadRouter(initialMatches: boolean) {
  vi.resetModules();
  const media = installMatchMedia(initialMatches);
  const { default: router } = await import("../router");
  return { media, router };
}

describe("responsive admin routing", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.resetModules();
    Object.defineProperty(window, "matchMedia", {
      configurable: true,
      writable: true,
      value: originalMatchMedia
    });
  });

  it.each([
    "/admin",
    "/admin/documents",
    "/admin/documents/document-1",
    "/admin/imports",
    "/admin/versions/version-1/edit"
  ])("redirects narrow viewports from %s to the reader", async (path) => {
    const { router } = await loadRouter(true);

    await router.push(path);

    expect(window.matchMedia).toHaveBeenCalledWith(ADMIN_MOBILE_MEDIA_QUERY);
    expect(router.currentRoute.value.fullPath).toBe("/reader");
  });

  it("keeps admin routes accessible above the 760px breakpoint", async () => {
    const { router } = await loadRouter(false);

    await router.push("/admin/documents");

    expect(router.currentRoute.value.fullPath).toBe("/admin/documents");
  });

  it("leaves the reader unchanged when a narrow viewport becomes wide", async () => {
    const { media, router } = await loadRouter(true);
    await router.push("/reader");

    media.setMatches(false);

    expect(router.currentRoute.value.fullPath).toBe("/reader");
  });

  it("redirects an open admin route when the viewport becomes narrow", async () => {
    const { media, router } = await loadRouter(false);
    await router.push("/admin/documents");

    media.setMatches(true);

    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe("/reader"));
  });

  it("keeps the detached preview open when the viewport becomes narrow", async () => {
    const { media, router } = await loadRouter(false);
    const previewPath = "/admin/versions/version-1/preview";

    await router.push(previewPath);
    media.setMatches(true);
    await Promise.resolve();

    expect(router.currentRoute.value.fullPath).toBe(previewPath);
  });
});
