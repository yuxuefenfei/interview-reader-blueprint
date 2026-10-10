import {afterEach, beforeEach, expect, test, vi} from "vitest";
import {flushPromises, mount, type VueWrapper} from "@vue/test-utils";
import {nextTick} from "vue";
import {consoleApi} from "./api";
import UploadReleaseView from "./views/UploadReleaseView.vue";
import type {Release} from "./types";

const {push, messages} = vi.hoisted(() => ({
    push: vi.fn(), messages: {success: vi.fn(), error: vi.fn(), warning: vi.fn()}
}));
vi.mock("vue-router", () => ({useRouter: () => ({push})}));
vi.mock("element-plus/es/components/message/index", () => ({ElMessage: messages}));

/** Drive browser transport events without sending files to a server. */
class UploadRequest extends EventTarget {
    static instances: UploadRequest[] = [];
    upload = new EventTarget();
    status = 0;
    response: unknown = null;
    responseType = "";
    open = vi.fn();
    send = vi.fn<(body: FormData) => void>();

    constructor() {
        super();
        UploadRequest.instances.push(this);
    }

    progress(loaded: number, total: number, lengthComputable = true): void {
        this.upload.dispatchEvent(new ProgressEvent("progress", {loaded, total, lengthComputable}));
    }

    uploaded(loaded: number, total: number, lengthComputable = true): void {
        this.upload.dispatchEvent(new ProgressEvent("load", {loaded, total, lengthComputable}));
    }

    respond(status: number, body: unknown): void {
        this.status = status;
        this.response = body;
        this.dispatchEvent(new Event("load"));
    }
}

const MiB = 1_048_576;
const release: Release = {
    id: "release-1", runId: 123, commit: "0123456789abcdef", sha256: "verified",
    bytes: 2 * MiB, stagedAt: "2026-10-10T09:00:00+08:00"
};
const wrappers: VueWrapper[] = [];
function latest(): UploadRequest { return UploadRequest.instances.at(-1)!; }
function jar(): File { return new File([new Uint8Array(2 * MiB)], "upgrade.jar"); }

beforeEach(() => {
    UploadRequest.instances = [];
    vi.stubGlobal("XMLHttpRequest", UploadRequest);
});
afterEach(() => {
    wrappers.splice(0).forEach(wrapper => wrapper.unmount());
    vi.unstubAllGlobals();
    vi.clearAllMocks();
});

async function form() {
    const wrapper = mount(UploadReleaseView, {
        global: {stubs: {AdminPageHeader: {template: "<header><slot name='actions'/></header>"}}}
    });
    wrappers.push(wrapper);
    await wrapper.get('input[name="run-id"]').setValue("123");
    const input = wrapper.get('input[type="file"]');
    Object.defineProperty(input.element, "files", {configurable: true, value: [jar()]});
    await input.trigger("change");
    const submit = wrapper.findAll("button").find(button => button.text().includes("上传并验证"))!;
    return {wrapper, submit};
}

test("reports actual bytes and waits for the verified response after upload completes", async () => {
    const progress = vi.fn();
    const settled = vi.fn();
    const request = consoleApi.upload(jar(), 123, progress);
    void request.then(settled, settled);
    const xhr = latest();
    expect(xhr.open).toHaveBeenCalledWith("POST", "/api/releases");
    expect(xhr.responseType).toBe("json");
    const body = xhr.send.mock.calls[0]![0];
    expect(body.get("file")).toMatchObject({name: "upgrade.jar", size: 2 * MiB});
    expect(body.get("runId")).toBe("123");

    xhr.progress(MiB, 2 * MiB);
    expect(progress).toHaveBeenLastCalledWith({phase: "uploading", loaded: MiB, total: 2 * MiB});
    xhr.uploaded(2 * MiB, 2 * MiB);
    expect(progress).toHaveBeenLastCalledWith({phase: "verifying", loaded: 2 * MiB, total: 2 * MiB});
    await Promise.resolve();
    expect(settled).not.toHaveBeenCalled();
    xhr.respond(201, release);
    await expect(request).resolves.toEqual(release);
});

test("does not invent a percentage when the browser cannot report a total", async () => {
    const progress = vi.fn();
    const request = consoleApi.upload(jar(), 123, progress);
    latest().progress(1024, 0, false);
    expect(progress).toHaveBeenLastCalledWith({phase: "uploading", loaded: 1024, total: null});
    latest().uploaded(0, 0, false);
    expect(progress).toHaveBeenLastCalledWith({phase: "verifying", loaded: 2 * MiB, total: 2 * MiB});
    latest().respond(201, release);
    await request;
});

test.each([
    {event: "error", status: 0, body: null, message: "上传连接失败"},
    {event: "abort", status: 0, body: null, message: "上传已中止"},
    {event: "timeout", status: 0, body: null, message: "上传请求超时"},
    {event: "load", status: 409, body: {error: "该运行已上传"}, message: "该运行已上传"},
    {event: "load", status: 413, body: null, message: "请求失败（HTTP 413）"},
    {event: "load", status: 200, body: null, message: "未收到有效的验证结果"}
])("rejects $event/$status failures with a user-facing message", async ({event, status, body, message}) => {
    const request = consoleApi.upload(jar(), 123);
    const rejection = expect(request).rejects.toThrow(message);
    latest().status = status;
    latest().response = body;
    latest().dispatchEvent(new Event(event));
    await rejection;
});

test("shows transfer progress then verification, and navigates only after success", async () => {
    const {wrapper, submit} = await form();
    expect(wrapper.find('[aria-label="升级包上传状态"]').exists()).toBe(false);
    await submit.trigger("click");
    expect(wrapper.get('[role="status"]').text()).toBe("正在上传升级包");
    expect(wrapper.get('[role="progressbar"]').attributes("aria-valuenow")).toBe("0");
    await submit.trigger("click");
    expect(UploadRequest.instances).toHaveLength(1);

    latest().progress(MiB, 2 * MiB);
    await nextTick();
    expect(wrapper.get('[role="progressbar"]').attributes("aria-valuenow")).toBe("50");
    expect(wrapper.get(".upload-progress-size").text()).toContain("1.0 MB / 2.0 MB · 50%");
    latest().progress(2 * MiB, 2 * MiB);
    await nextTick();
    expect(wrapper.get('[role="progressbar"]').attributes("aria-valuenow")).toBe("99");
    latest().uploaded(2 * MiB, 2 * MiB);
    await nextTick();
    expect(wrapper.get('[role="status"]').text()).toBe("正在校验升级包");
    expect(wrapper.get('[role="progressbar"]').attributes("aria-valuenow")).toBe("100");
    expect(submit.attributes("disabled")).toBeDefined();
    expect(push).not.toHaveBeenCalled();
    expect(messages.success).not.toHaveBeenCalled();

    latest().respond(201, release);
    await flushPromises();
    expect(messages.success).toHaveBeenCalledOnce();
    expect(push).toHaveBeenCalledWith("/releases");
});

test("keeps the chosen file and run id after failed verification and resets progress for retry", async () => {
    const {wrapper, submit} = await form();
    await submit.trigger("click");
    latest().uploaded(2 * MiB, 2 * MiB);
    latest().respond(400, {error: "SHA-256 校验失败"});
    await flushPromises();
    expect(messages.error).toHaveBeenCalledWith("SHA-256 校验失败");
    expect(wrapper.get('[role="status"]').text()).toContain("失败，可重试");
    expect(wrapper.get('[role="progressbar"]').attributes("aria-valuenow")).toBe("100");
    expect(wrapper.get('input[name="run-id"]').element).toHaveProperty("value", "123");
    expect(wrapper.text()).toContain("upgrade.jar");
    expect(submit.attributes("disabled")).toBeUndefined();
    expect(push).not.toHaveBeenCalled();

    await submit.trigger("click");
    expect(UploadRequest.instances).toHaveLength(2);
    expect(latest().send.mock.calls[0]![0].get("runId")).toBe("123");
    expect(latest().send.mock.calls[0]![0].get("file")).toMatchObject({name: "upgrade.jar"});
    expect(wrapper.get('[role="progressbar"]').attributes("aria-valuenow")).toBe("0");
    latest().respond(201, release);
    await flushPromises();
});
