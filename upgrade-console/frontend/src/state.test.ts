import {afterEach, expect, test, vi} from "vitest";
import {consoleState} from "./state";

class FakeEventSource {
    static latest: FakeEventSource;
    readonly url: string;
    onopen: (() => void) | null = null;
    onerror: (() => void) | null = null;
    private listeners = new Map<string, (event: MessageEvent<string>) => void>();

    constructor(url: string) {
        this.url = url;
        FakeEventSource.latest = this;
    }

    addEventListener(name: string, listener: EventListenerOrEventListenerObject): void {
        this.listeners.set(name, listener as (event: MessageEvent<string>) => void);
    }

    emit(name: string, value: unknown): void {
        this.listeners.get(name)?.(new MessageEvent(name, {data: JSON.stringify(value)}));
    }

    close(): void {
    }
}

const operation = {
    id: "op-1", releaseId: "release-1", status: "RUNNING", stage: "STARTING", backupId: null,
    message: "启动中", startedAt: "2026-09-30T09:00:00Z", updatedAt: "2026-09-30T09:00:00Z", events: []
};
const initial = {
    health: {
        checkedAt: "2026-09-30T09:00:00Z", overall: {status: "UP"},
        liveness: {status: "UP"}, readiness: {status: "UP"}, drain: {}, eligible: true, blockers: []
    },
    latestOperation: operation,
    feedCursor: 1
};

afterEach(() => {
    consoleState.stop();
    consoleState.dashboard.value = null;
    consoleState.feeds.clear();
    consoleState.olderCursors.clear();
    consoleState.operationDetails.clear();
    vi.unstubAllGlobals();
});

test("replays from the H2 cursor and merges live command output without duplicates", async () => {
    const fetcher = vi.fn(async (input: string | URL | Request) => {
        const path = String(input);
        const body = path === "/api/dashboard" ? initial
            : path.includes("/feed?") ? {
                    entries: [{
                        id: 1,
                        operationId: "op-1",
                        at: "2026-09-30T09:00:00Z",
                        kind: "STAGE",
                        stage: "STARTING",
                        message: "启动中"
                    }], nextBeforeId: null
                }
                : operation;
        return new Response(JSON.stringify(body), {status: 200, headers: {"Content-Type": "application/json"}});
    });
    vi.stubGlobal("fetch", fetcher);
    vi.stubGlobal("EventSource", FakeEventSource);

    await consoleState.start();
    await vi.waitFor(() => expect(consoleState.feeds.get("op-1")).toHaveLength(1));
    expect(FakeEventSource.latest.url).toBe("/api/feed/stream?afterId=1");

    const line = {
        id: 2,
        operationId: "op-1",
        at: "2026-09-30T09:00:01Z",
        kind: "COMMAND_STDOUT",
        stage: null,
        message: "started"
    };
    FakeEventSource.latest.emit("entry", line);
    FakeEventSource.latest.emit("entry", line);
    expect(consoleState.feeds.get("op-1")?.map((entry) => entry.id)).toEqual([1, 2]);
});