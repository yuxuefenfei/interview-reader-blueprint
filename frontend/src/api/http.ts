import axios, { AxiosError } from "axios";

export type AppErrorKind = "validation" | "auth" | "forbidden" | "not-found" | "conflict" | "rate-limit" | "network" | "server" | "unknown";

export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  code?: string;
  traceId?: string;
  fieldErrors?: Record<string, string>;
}

export class AppError extends Error {
  constructor(
    message: string,
    public readonly kind: AppErrorKind,
    public readonly status: number | undefined,
    public readonly code: string,
    public readonly traceId: string | undefined,
    public readonly fieldErrors: Record<string, string> | undefined,
    public readonly retryable: boolean,
    public readonly cause: unknown
  ) {
    super(message);
    this.name = code;
  }
}

export const http = axios.create({ baseURL: "/api", withCredentials: true, timeout: 30_000 });

export function normalizeHttpError(error: AxiosError<ProblemDetail>): AppError {
  const status = error.response?.status;
  const problem = error.response?.data;
  const detail = problem?.detail || (status ? "请求失败" : error.message || "网络连接失败");
  const traceId = problem?.traceId;
  const suffix = traceId ? `（追踪号：${traceId.slice(0, 8)}）` : "";
  return new AppError(
    `${detail}${suffix}`,
    errorKind(status),
    status,
    problem?.code || (status ? "REQUEST_FAILED" : "NETWORK_ERROR"),
    traceId,
    problem?.fieldErrors,
    status === undefined || status === 429 || status >= 500,
    error
  );
}

http.interceptors.response.use(
  (response) => response,
  (error: AxiosError<ProblemDetail>) => Promise.reject(normalizeHttpError(error))
);

function errorKind(status: number | undefined): AppErrorKind {
  if (status === undefined) return "network";
  if (status === 400 || status === 422) return "validation";
  if (status === 401) return "auth";
  if (status === 403) return "forbidden";
  if (status === 404) return "not-found";
  if (status === 409) return "conflict";
  if (status === 429) return "rate-limit";
  if (status >= 500) return "server";
  return "unknown";
}