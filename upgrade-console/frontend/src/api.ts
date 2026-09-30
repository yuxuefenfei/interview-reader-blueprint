import type {Dashboard, FeedPage, Operation, Page, Release} from "./types";

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
    const response = await fetch(path, {cache: "no-store", ...options});
    const body = await response.json().catch(() => ({}));
    if (!response.ok) {
        const message = typeof body?.error === "string" ? body.error : `请求失败（HTTP ${response.status}）`;
        throw new Error(message);
    }
    return body as T;
}

function confirmation(backupId: string | null): RequestInit {
    return {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({backupId, confirmDataRestore: true})
    };
}

export const consoleApi = {
    dashboard: () => request<Dashboard>("/api/dashboard"),
    releases: (query: string, page: number) =>
        request<Page<Release>>(`/api/releases?query=${encodeURIComponent(query)}&page=${page}&size=20`),
    release: (id: string) => request<Release>(`/api/releases/${encodeURIComponent(id)}`),
    operations: (page: number, releaseId?: string) =>
        request<Page<Operation>>(releaseId
            ? `/api/releases/${encodeURIComponent(releaseId)}/operations?page=${page}&size=20`
            : `/api/operations?page=${page}&size=20`),
    operation: (id: string) => request<Operation>(`/api/operations/${encodeURIComponent(id)}`),
    feed: (id: string, beforeId = 0) =>
        request<FeedPage>(`/api/operations/${encodeURIComponent(id)}/feed?beforeId=${beforeId}`),
    upload: (file: File, runId: number) => {
        const form = new FormData();
        form.append("file", file);
        form.append("runId", String(runId));
        return request<Release>("/api/releases", {method: "POST", body: form});
    },
    deploy: (id: string) =>
        request<Operation>(`/api/releases/${encodeURIComponent(id)}/deployments`, {method: "POST"}),
    restorePublished: (backupId: string) =>
        request<Operation>("/api/recoveries", confirmation(backupId)),
    abortInterrupted: (id: string) =>
        request<Operation>(`/api/operations/${encodeURIComponent(id)}/abort`, confirmation(null)),
    recover: (id: string, backupId: string) =>
        request<Operation>(`/api/operations/${encodeURIComponent(id)}/recover`, confirmation(backupId))
};

export function userMessage(error: unknown, fallback: string): string {
    return error instanceof Error && error.message ? error.message : fallback;
}