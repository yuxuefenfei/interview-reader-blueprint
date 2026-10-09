import {afterEach, beforeEach, expect, test, vi} from "vitest";
import {flushPromises, mount, type VueWrapper} from "@vue/test-utils";
import {nextTick} from "vue";
import OperationFeed from "./components/OperationFeed.vue";
import DashboardView from "./views/DashboardView.vue";
import {consoleApi} from "./api";
import {consoleState} from "./state";
import type {FeedEntry, Operation} from "./types";

vi.mock("vue-router", () => ({useRouter: () => ({push: vi.fn()})}));

const at = "2026-10-09T15:07:00+08:00";
const wrappers: VueWrapper[] = [];
const global = {
    stubs: {
        ElButton: {template: "<button><slot /></button>"},
        ElTag: {template: "<span><slot /></span>"},
        ElTooltip: {template: "<span><slot /></span>"},
        ElAlert: {template: "<aside><slot /></aside>"},
        AdminPageHeader: {template: "<header><slot name='actions' /></header>"}
    },
    directives: {loading: () => undefined}
};

function operation(id = "op-1", status = "RUNNING"): Operation {
    return {
        id, status, releaseId: "release-1", stage: "STARTING", backupId: null,
        message: "启动新版本", startedAt: at, updatedAt: at, events: []
    };
}

function entry(id: number, kind: FeedEntry["kind"] = "STAGE", operationId = "op-1"): FeedEntry {
    return {id, kind, operationId, at, stage: kind === "STAGE" ? "STARTING" : null, message: "记录 " + id};
}

function seed(current = operation(), records = [entry(10), entry(11, "COMMAND_STDOUT")]): void {
    consoleState.operationDetails.set(current.id, current);
    consoleState.feeds.set(current.id, records);
    consoleState.olderCursors.set(current.id, null);
    consoleState.dashboard.value = {
        feedCursor: 11, latestOperation: current,
        health: {
            checkedAt: at, overall: {status: "UP"}, liveness: {status: "UP"},
            readiness: {status: "UP", components: {db: {status: "UP"}, diskSpace: {status: "UP"}}},
            drain: {maintenance: false, releaseCommit: "dccce651634f", activeWrites: 0, importJobs: 2, deletionJobs: 1},
            eligible: true, blockers: []
        }
    };
}

/** jsdom has no layout: model a scrollable viewport, including browser scrollTop clamping. */
function viewport(node: HTMLElement, rowSelector: string): HTMLElement {
    let top = 0;
    Object.defineProperties(node, {
        clientHeight: {configurable: true, get: () => 200},
        scrollHeight: {configurable: true, get: () => 400 + node.querySelectorAll(rowSelector).length * 100},
        scrollTop: {
            configurable: true, get: () => top,
            set: (value: number) => { top = Math.max(0, Math.min(value, node.scrollHeight - node.clientHeight)); }
        }
    });
    for (const row of node.querySelectorAll<HTMLElement>(rowSelector)) {
        Object.defineProperty(row, "offsetTop", {
            configurable: true, get: () => [...node.querySelectorAll(rowSelector)].indexOf(row) * 100
        });
    }
    return node;
}

function panes(wrapper: VueWrapper) {
    return {
        stage: viewport(wrapper.get(".stage-track").element as HTMLElement, "li"),
        log: viewport(wrapper.get(".log-terminal").element as HTMLElement, ".log-line")
    };
}

function append(record: FeedEntry): void {
    consoleState.feeds.set(record.operationId, [...(consoleState.feeds.get(record.operationId) ?? []), record]);
}

beforeEach(() => seed());
afterEach(() => {
    for (const wrapper of wrappers.splice(0)) wrapper.unmount();
    consoleState.dashboard.value = null;
    consoleState.feeds.clear();
    consoleState.olderCursors.clear();
    consoleState.operationDetails.clear();
    consoleState.error.value = "";
    vi.restoreAllMocks();
});

test("follows new stages and output independently, pausing only the pane the user scrolls", async () => {
    const wrapper = mount(OperationFeed, {props: {operationId: "op-1"}, global});
    wrappers.push(wrapper);
    await flushPromises();
    const {stage, log} = panes(wrapper);

    append(entry(12));
    await nextTick();
    expect(stage.scrollTop).toBe(stage.scrollHeight - stage.clientHeight);
    expect(log.scrollTop).toBe(log.scrollHeight - log.clientHeight);

    stage.scrollTop = 80;
    stage.dispatchEvent(new Event("scroll"));
    await nextTick();
    append(entry(13));
    await nextTick();
    expect(stage.scrollTop).toBe(80);
    expect(log.scrollTop).toBe(log.scrollHeight - log.clientHeight);
    expect(wrapper.get('[aria-label="升级阶段"]').text()).toContain("已暂停跟随");
    await wrapper.get('[aria-label="升级阶段"] button').trigger("click");
    expect(stage.scrollTop).toBe(stage.scrollHeight - stage.clientHeight);

    // Command output does not reposition the stage pane; duplicate tail IDs do not reposition either pane.
    stage.scrollTop = 90;
    append(entry(14, "COMMAND_STDOUT"));
    await nextTick();
    expect(stage.scrollTop).toBe(90);
    log.scrollTop = 70;
    consoleState.feeds.set("op-1", [...consoleState.feeds.get("op-1")!]);
    await nextTick();
    expect(log.scrollTop).toBe(70);
});

test("prepending older history preserves both visible records", async () => {
    consoleState.olderCursors.set("op-1", 10);
    const wrapper = mount(OperationFeed, {props: {operationId: "op-1", compact: true}, global});
    wrappers.push(wrapper);
    await flushPromises();
    const {stage, log} = panes(wrapper);
    stage.scrollTop = 80;
    log.scrollTop = 100;
    stage.dispatchEvent(new Event("scroll"));
    log.dispatchEvent(new Event("scroll"));
    vi.spyOn(consoleApi, "feed").mockImplementation(async () => {
        append(entry(12, "COMMAND_STDOUT"));
        return {entries: [entry(1), entry(2, "COMMAND_STDOUT")], nextBeforeId: null};
    });

    expect(wrapper.find(".operation-feed-heading").exists()).toBe(false);
    await wrapper.get('[aria-label="命令日志"] button').trigger("click");
    await flushPromises();
    expect(stage.scrollTop).toBe(180);
    expect(log.scrollTop).toBe(300);
    expect(consoleApi.feed).toHaveBeenCalledWith("op-1", 10);
});

test("opens inactive history at the top and follows manual recovery or a new running operation", async () => {
    seed(operation("op-1", "NEEDS_OPERATOR"));
    const wrapper = mount(OperationFeed, {props: {operationId: "op-1"}, global});
    wrappers.push(wrapper);
    await flushPromises();
    const {stage, log} = panes(wrapper);
    append(entry(12));
    await nextTick();
    expect(stage.scrollTop).toBe(0);
    expect(log.scrollTop).toBe(0);
    expect(wrapper.find("li.current").exists()).toBe(false);

    consoleState.operationDetails.set("op-1", {...operation(), stage: "RECOVERING"});
    await nextTick();
    expect(stage.scrollTop).toBe(stage.scrollHeight - stage.clientHeight);
    expect(log.scrollTop).toBe(log.scrollHeight - log.clientHeight);
    stage.scrollTop = 0;
    stage.dispatchEvent(new Event("scroll"));

    const second = operation("op-2");
    consoleState.operationDetails.set(second.id, second);
    consoleState.feeds.set(second.id, [entry(20, "STAGE", second.id)]);
    consoleState.olderCursors.set(second.id, null);
    await wrapper.setProps({operationId: second.id});
    await flushPromises();
    expect(stage.scrollTop).toBe(stage.scrollHeight - stage.clientHeight);
    expect(log.scrollTop).toBe(log.scrollHeight - log.clientHeight);
});

test("prioritizes the active operation and preserves its feed and scroll on completion", async () => {
    const wrapper = mount(DashboardView, {global});
    wrappers.push(wrapper);
    await flushPromises();
    const feed = wrapper.get(".operation-feed").element;
    const {stage, log} = panes(wrapper);
    stage.scrollTop = 90;
    log.scrollTop = 70;
    stage.dispatchEvent(new Event("scroll"));
    log.dispatchEvent(new Event("scroll"));
    expect(wrapper.get(".upgrade-condition").text()).toContain("操作进行中");
    expect(wrapper.get(".dashboard-stack").element.children[1].className).toBe("latest-section");

    const completed = {...operation(), status: "SUCCEEDED", stage: "SUCCEEDED", message: "升级完成，写入已恢复"};
    consoleState.operationDetails.set(completed.id, completed);
    consoleState.dashboard.value = {...consoleState.dashboard.value!, latestOperation: completed};
    await nextTick();
    expect(wrapper.get(".dashboard-stack").element.children[1].className).toBe("metrics-section");
    expect(wrapper.get(".operation-feed").element).toBe(feed);
    expect(stage.scrollTop).toBe(90);
    expect(log.scrollTop).toBe(70);
    expect(wrapper.get(".latest-summary").text().match(/升级成功/g)).toHaveLength(1);
    expect(wrapper.get(".upgrade-condition").text()).toBe("当前满足升级条件");
    expect(wrapper.get(".metrics-section").text()).toContain("导入 2 · 删除 1");
    expect(wrapper.get(".metrics-section").text()).toContain("每 15 秒更新");

    const interrupted = {...completed, status: "NEEDS_OPERATOR", message: "请检查旧版并恢复写入"};
    consoleState.operationDetails.set(interrupted.id, interrupted);
    consoleState.dashboard.value = {...consoleState.dashboard.value!, latestOperation: interrupted};
    await nextTick();
    expect(wrapper.get(".upgrade-condition").text()).toBe("需先处理上次操作");
    expect(wrapper.get(".latest-summary").classes()).toContain("result-danger");
});
