import axios, { AxiosError } from "axios";

export const http = axios.create({
  baseURL: "/api",
  withCredentials: true,
  timeout: 30_000
});

http.interceptors.response.use(
  (response) => response,
  (error: AxiosError<{ error?: string; message?: string }>) => {
    const message = error.response?.data?.error || error.response?.data?.message || error.message || "请求失败";
    return Promise.reject(new Error(message));
  }
);