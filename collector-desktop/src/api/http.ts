import axios, { AxiosHeaders, type AxiosRequestConfig } from "axios";

import type { ApiResult } from "@/types/api";

export const DEFAULT_SERVER_URL = "http://127.0.0.1:9090/collector";

let currentServerUrl = DEFAULT_SERVER_URL;
let currentToken = "";

export class ApiRequestError extends Error {
  httpStatus?: number;
  code?: number;
  body?: unknown;

  constructor(message: string, options: { httpStatus?: number; code?: number; body?: unknown } = {}) {
    super(message);
    this.name = "ApiRequestError";
    this.httpStatus = options.httpStatus;
    this.code = options.code;
    this.body = options.body;
  }
}

export interface ConnectionTestResult {
  healthOk: boolean;
  authOk: boolean;
  message: string;
}

export function normalizeServerUrl(serverUrl: string): string {
  const trimmed = serverUrl.trim().replace(/\/+$/, "");
  if (!trimmed) {
    return DEFAULT_SERVER_URL;
  }
  try {
    const parsed = new URL(trimmed);
    if (parsed.port === "9090" && (parsed.pathname === "" || parsed.pathname === "/")) {
      parsed.pathname = "/collector";
      return parsed.toString().replace(/\/+$/, "");
    }
  } catch {
    return trimmed;
  }
  return trimmed;
}

export function configureHttp(config: { serverUrl?: string; token?: string }): void {
  if (config.serverUrl !== undefined) {
    currentServerUrl = normalizeServerUrl(config.serverUrl);
  }
  if (config.token !== undefined) {
    currentToken = config.token.trim();
  }
}

export function getHttpConfig(): { serverUrl: string; token: string } {
  return {
    serverUrl: currentServerUrl,
    token: currentToken
  };
}

const client = axios.create({
  timeout: 8000
});

client.interceptors.request.use((config) => {
  config.baseURL = currentServerUrl;
  config.headers = AxiosHeaders.from(config.headers);
  if (currentToken) {
    config.headers.set("X-Collector-Token", currentToken);
  }
  return config;
});

export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  try {
    const response = await client.request<ApiResult<T> | T>(config);
    return unwrapApiResponse<T>(response.data);
  } catch (error) {
    if (axios.isAxiosError(error)) {
      const httpStatus = error.response?.status;
      if (error.response?.data) {
        try {
          return unwrapApiResponse<T>(error.response.data as ApiResult<T> | T, httpStatus);
        } catch (apiError) {
          throw apiError;
        }
      }
      throw new ApiRequestError(resolveNetworkMessage(error.message), { httpStatus });
    }
    throw error;
  }
}

export function unwrapApiResponse<T>(body: ApiResult<T> | T, httpStatus?: number): T {
  if (body && typeof body === "object") {
    const apiBody = body as ApiResult<T>;
    const status = String(apiBody.status || "").toLowerCase();
    const code = typeof apiBody.code === "number" ? apiBody.code : undefined;
    if (status === "error" || (code !== undefined && code !== 200)) {
      throw new ApiRequestError(localizeApiMessage(apiBody.message, httpStatus || code), {
        httpStatus,
        code,
        body
      });
    }
    if (Object.prototype.hasOwnProperty.call(apiBody, "data")) {
      return apiBody.data as T;
    }
  }
  return body as T;
}

export async function testServerConnection(): Promise<ConnectionTestResult> {
  const health = await request<unknown>({ url: "/health", method: "GET" });
  if (!currentToken) {
    return {
      healthOk: Boolean(health),
      authOk: false,
      message: "服务可访问，但管理接口令牌未填写"
    };
  }
  await request<unknown>({ url: "/api/protocols", method: "GET" });
  return {
    healthOk: true,
    authOk: true,
    message: "服务连接和接口鉴权成功"
  };
}

function localizeApiMessage(message?: string, status?: number): string {
  const normalized = String(message || "").trim();
  if (normalized === "missing credential" || normalized === "invalid ops token" || status === 401) {
    return "接口访问令牌缺失或无效";
  }
  if (status === 403) {
    return normalized || "权限不足";
  }
  return normalized || "请求失败";
}

function resolveNetworkMessage(message: string): string {
  if (/network error/i.test(message) || /connection refused/i.test(message)) {
    return "无法连接采集服务，请检查服务地址和后端是否已启动";
  }
  if (/timeout/i.test(message)) {
    return "请求采集服务超时";
  }
  return message || "请求失败";
}
