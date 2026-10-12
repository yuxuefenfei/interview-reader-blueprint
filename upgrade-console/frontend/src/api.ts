import type {Dashboard, FeedPage, Operation, Page, Release} from "./types";

export interface UploadProgress {
    phase: "uploading" | "verifying";
    loaded: number;
    total: number | null;
}

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
    upload: (file: File, runId: number, onProgress?: (progress: UploadProgress) => void): Promise<Release> =>
        new Promise((resolve, reject) => {
            const form = new FormData();
            form.append("file", file);
            form.append("runId", String(runId));
            const xhr = new XMLHttpRequest();
            xhr.upload.addEventListener("progress", event => onProgress?.({
                phase: "uploading", loaded: event.loaded, total: event.lengthComputable ? event.total : null
            }));
            // 此时仅完成请求体上传；验证结果仍需由最终响应确认。
            xhr.upload.addEventListener("load", event => onProgress?.({
                phase: "verifying", loaded: event.lengthComputable ? event.loaded : file.size,
                total: event.lengthComputable ? event.total : file.size
            }));
            xhr.addEventListener("load", () => {
                const body = xhr.response as (Release & {error?: unknown}) | null;
                if (xhr.status >= 200 && xhr.status < 300) {
                    if (body && typeof body.id === "string") resolve(body);
                    else reject(new Error("未收到有效的验证结果，请查看升级包列表确认"));
                } else {
                    reject(new Error(typeof body?.error === "string" ? body.error : `请求失败（HTTP ${xhr.status}）`));
                }
            });
            xhr.addEventListener("error", () => reject(new Error("上传连接失败，请检查网络后重试")));
            xhr.addEventListener("abort", () => reject(new Error("上传已中止")));
            xhr.addEventListener("timeout", () => reject(new Error("上传请求超时，请查看升级包列表确认结果")));
            xhr.open("POST", "/api/releases");
            xhr.responseType = "json";
            // 由浏览器设置 multipart 边界，并携带现有的同源凭据。
            xhr.send(form);
        }),
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