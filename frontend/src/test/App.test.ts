import { flushPromises, mount } from "@vue/test-utils";
import { defineComponent } from "vue";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  session: vi.fn(),
  activateUpdate: vi.fn(),
  syncDeletedDocuments: vi.fn()
}));

vi.mock("vue-router", () => ({
  useRouter: () => ({ replace: vi.fn() })
}));

vi.mock("../api/reader", () => ({
  readerApi: {
    session: mocks.session,
    login: vi.fn(),
    logout: vi.fn()
  }
}));

vi.mock("../offline/deletionSync", () => ({
  syncDeletedDocuments: mocks.syncDeletedDocuments
}));

vi.mock("../offline/serviceWorkerRegistration", () => ({
  SERVICE_WORKER_UPDATE_EVENT: "interview-reader-update-available",
  activateServiceWorkerUpdate: mocks.activateUpdate
}));

import App from "../App.vue";
import { BRAND_ICON_URL } from "../shared/branding";

const ButtonStub = defineComponent({
  emits: ["click"],
  template: '<button @click="$emit(\'click\')"><slot /></button>'
});

function mountApp() {
  return mount(App, {
    global: {
      stubs: {
        ElAlert: true,
        ElButton: ButtonStub,
        ElInput: true,
        RouterView: true
      }
    }
  });
}

describe("App update notification", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.syncDeletedDocuments.mockResolvedValue(undefined);
  });

  it("does not show an application update on the login page", async () => {
    mocks.session.mockResolvedValue({ authenticated: false, username: null });
    const wrapper = mountApp();
    await flushPromises();

    window.dispatchEvent(new Event("interview-reader-update-available"));
    await flushPromises();

    expect(wrapper.find(".app-update-banner").exists()).toBe(false);
    wrapper.unmount();
  });

  it("uses the shared SVG brand icon on the login page", async () => {
    mocks.session.mockResolvedValue({ authenticated: false, username: null });
    const wrapper = mountApp();
    await flushPromises();

    const brand = wrapper.get("img.brand-mark");
    expect(brand.attributes("src")).toBe(BRAND_ICON_URL);
    expect(brand.attributes("alt")).toBe("");
    wrapper.unmount();
  });

  it("lets an authenticated user activate a waiting update", async () => {
    mocks.session.mockResolvedValue({ authenticated: true, username: "admin" });
    const wrapper = mountApp();
    await flushPromises();

    window.dispatchEvent(new Event("interview-reader-update-available"));
    await flushPromises();

    const banner = wrapper.get(".app-update-banner");
    expect(banner.text()).toContain("新版本已准备好");
    await banner.get("button").trigger("click");
    expect(mocks.activateUpdate).toHaveBeenCalledOnce();
    wrapper.unmount();
  });
});
