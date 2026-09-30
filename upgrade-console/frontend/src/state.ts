import {reactive, ref} from "vue";
import {consoleApi, userMessage} from "./api";
import type {Dashboard, FeedEntry, Operation} from "./types";

const dashboard = ref<Dashboard | null>(null);
const loading = ref(false);
const error = ref("");
const streamStatus = ref<"connecting" | "live" | "reconnecting">("connecting");
const feeds = reactive(new Map<string, FeedEntry[]>());
const olderCursors = reactive(new Map<string, number | null>());
const operationDetails = reactive(new Map<string, Operation>());
let stream: EventSource | null = null;
let healthTimer: number | null = null;
let refreshing = false;

/** Operation output arrives by SSE; the slower dashboard refresh samples health metrics. */
async function refreshDashboard(): Promise<void> {
    if (refreshing) return;
    refreshing = true;
    loading.value = dashboard.value === null;
    try {
        const next = await consoleApi.dashboard();
        dashboard.value = next;
        error.value = "";
        if (next.latestOperation) {
            operationDetails.set(next.latestOperation.id, next.latestOperation);
            void ensureFeed(next.latestOperation.id);
        }
    } catch (caught) {
        error.value = userMessage(caught, "获取控制台状态失败");
    } finally {
        refreshing = false;
        loading.value = false;
    }
}

function mergeFeed(operationId: string, incoming: FeedEntry[]): void {
    const byId = new Map((feeds.get(operationId) ?? []).map((entry) => [entry.id, entry]));
    for (const entry of incoming) byId.set(entry.id, entry);
    feeds.set(operationId, [...byId.values()].sort((left, right) => left.id - right.id));
}

async function ensureFeed(operationId: string): Promise<void> {
    if (olderCursors.has(operationId)) return;
    try {
        const page = await consoleApi.feed(operationId);
        mergeFeed(operationId, page.entries);
        olderCursors.set(operationId, page.nextBeforeId);
    } catch (caught) {
        error.value = userMessage(caught, "获取升级日志失败");
    }
}

async function loadOlder(operationId: string): Promise<void> {
    const cursor = olderCursors.get(operationId);
    if (!cursor) return;
    const page = await consoleApi.feed(operationId, cursor);
    mergeFeed(operationId, page.entries);
    olderCursors.set(operationId, page.nextBeforeId);
}

async function loadOperation(operationId: string): Promise<Operation> {
    const operation = await consoleApi.operation(operationId);
    operationDetails.set(operationId, operation);
    await ensureFeed(operationId);
    return operation;
}

function receive(entry: FeedEntry): void {
    mergeFeed(entry.operationId, [entry]);
    if (entry.kind !== "STAGE") return;
    void consoleApi.operation(entry.operationId).then((operation) => {
        operationDetails.set(operation.id, operation);
        if (dashboard.value?.latestOperation?.id === operation.id) {
            dashboard.value = {...dashboard.value, latestOperation: operation};
        } else {
            void refreshDashboard();
        }
    }).catch(() => {
        void refreshDashboard();
    });
}

async function start(): Promise<void> {
    await refreshDashboard();
    if (stream || !dashboard.value) return;
    streamStatus.value = "connecting";
    stream = new EventSource(`/api/feed/stream?afterId=${dashboard.value.feedCursor}`);
    stream.addEventListener("entry", (event) => {
        try {
            receive(JSON.parse((event as MessageEvent<string>).data) as FeedEntry);
        } catch {
            error.value = "实时日志内容无法解析，请刷新页面";
        }
    });
    stream.onopen = () => {
        streamStatus.value = "live";
    };
    stream.onerror = () => {
        streamStatus.value = "reconnecting";
    };
    healthTimer = window.setInterval(() => {
        void refreshDashboard();
    }, 15_000);
}

function stop(): void {
    stream?.close();
    stream = null;
    if (healthTimer !== null) window.clearInterval(healthTimer);
    healthTimer = null;
}

export const consoleState = {
    dashboard, loading, error, streamStatus, feeds, olderCursors, operationDetails,
    start, stop, refreshDashboard, ensureFeed, loadOlder, loadOperation
};