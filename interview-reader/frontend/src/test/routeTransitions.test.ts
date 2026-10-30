import {enableAutoUnmount, flushPromises, mount} from "@vue/test-utils";
import {defineComponent, h, onMounted} from "vue";
import {createMemoryHistory, createRouter, isNavigationFailure, onBeforeRouteLeave} from "vue-router";
import {afterEach, beforeEach, describe, expect, it, vi} from "vitest";
import App from "../App.vue";

vi.mock("../api/reader", () => ({readerApi: {
    session: () => Promise.resolve({authenticated: true, username: "admin"}),
}}));
vi.mock("../offline/deletionSync", () => ({syncDeletedDocuments: () => Promise.resolve()}));
enableAutoUnmount(afterEach);

const readerMounted = vi.fn();
const Reader = defineComponent({
    setup() {
        onMounted(readerMounted);
        return () => h("section", {"data-page": "reader"}, "正文");
    },
});
const FastPage = defineComponent({render: () => h("section", {"data-page": "fast"}, "管理页面")});
const GuardedPage = defineComponent({
    setup() {
        onBeforeRouteLeave(() => false);
        return () => h("section", {"data-page": "guarded"}, "尚未保存");
    },
});

async function openApp(initial = "/reader", loadSlow = () => Promise.resolve(FastPage)) {
    const router = createRouter({
        history: createMemoryHistory(),
        routes: [
            {path: "/reader", component: Reader},
            {path: "/fast", component: FastPage},
            {path: "/guarded", component: GuardedPage},
            {path: "/slow", component: loadSlow},
        ],
    });
    await router.push(initial);
    await router.isReady();
    const wrapper = mount(App, {global: {plugins: [router], stubs: {Transition: false}}});
    await flushPromises();
    return {router, wrapper};
}

describe("route transition lifecycle", () => {
    beforeEach(() => readerMounted.mockClear());

    it("preserves the reader instance and reading surface across section queries and history", async () => {
        const {router, wrapper} = await openApp();
        const article = wrapper.get('[data-page="reader"]').element;
        await router.push("/reader?section=next&block=target");
        await flushPromises();
        expect(wrapper.get('[data-page="reader"]').element).toBe(article);
        router.back();
        await vi.waitFor(() => expect(router.currentRoute.value.query).toEqual({}));
        expect(wrapper.get('[data-page="reader"]').element).toBe(article);
        expect(readerMounted).toHaveBeenCalledOnce();
    });

    it("keeps the visible page when a leave guard rejects navigation", async () => {
        const {router, wrapper} = await openApp("/guarded");
        const page = wrapper.get('[data-page="guarded"]').element;
        expect(isNavigationFailure(await router.push("/fast"))).toBe(true);
        await flushPromises();
        expect(wrapper.get('[data-page="guarded"]').element).toBe(page);
        expect(wrapper.find(".app-shell-leave-active").exists()).toBe(false);
        expect(wrapper.find('[data-page="fast"]').exists()).toBe(false);
    });

    it("does not reveal a stale lazy page after a newer navigation completes", async () => {
        let resolveSlow!: (value: typeof FastPage) => void;
        const slow = new Promise<typeof FastPage>((resolve) => { resolveSlow = resolve; });
        const {router, wrapper} = await openApp("/reader", () => slow);
        const pending = router.push("/slow");
        await flushPromises();
        expect(wrapper.find('[data-page="reader"]').exists()).toBe(true);
        await router.push("/fast");
        resolveSlow(FastPage);
        expect(isNavigationFailure(await pending)).toBe(true);
        await vi.waitFor(() => expect(wrapper.find('[data-page="fast"]').exists()).toBe(true));
        expect(router.currentRoute.value.path).toBe("/fast");
        expect(wrapper.findAll("[data-page]")).toHaveLength(1);
        expect(wrapper.get("#main-content").attributes("tabindex")).toBe("-1");
    });
});
