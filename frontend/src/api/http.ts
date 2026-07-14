import axios, { AxiosError } from "axios";

type Problem = { detail?: string; error?: string; message?: string; code?: string; traceId?: string };

export const http = axios.create({ baseURL: "/api", withCredentials: true, timeout: 30_000 });

http.interceptors.response.use(
  (response) => response,
  (error: AxiosError<Problem>) => {
    const problem = error.response?.data;
    const detail = problem?.detail || problem?.error || problem?.message || error.message || "请求失败";
    const suffix = problem?.traceId ? `（追踪号：${problem.traceId.slice(0, 8)}）` : "";
    const normalized = new Error(`${detail}${suffix}`);
    normalized.name = problem?.code || "REQUEST_FAILED";
    return Promise.reject(normalized);
  }
);