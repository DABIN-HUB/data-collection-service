import { normalizeServerConfig } from "./main-utils.js";

export interface CollectorProxyRequest {
  serverUrl: string;
  url: string;
  method?: string;
  params?: Record<string, unknown>;
  data?: unknown;
  headers?: Record<string, string>;
  token?: string;
  timeoutMs?: number;
}

export interface CollectorProxyResponse {
  status: number;
  statusText: string;
  headers: Record<string, string>;
  body: unknown;
}

const ALLOWED_REQUEST_HEADERS = new Set(["accept", "content-type"]);
const DEFAULT_PROXY_TIMEOUT_MS = 8000;
const MAX_PROXY_TIMEOUT_MS = 30000;

/**
 * 构造主进程代理请求地址，并限制只能访问当前采集服务上下文。
 */
export function buildCollectorProxyUrl(serverUrl: string, rawUrl: string, params?: Record<string, unknown>): URL {
  const base = new URL(normalizeServerConfig({ serverUrl }).serverUrl);
  const trimmed = String(rawUrl || "").trim();
  if (!trimmed) {
    throw new Error("代理请求路径不能为空");
  }

  const target = resolveTargetUrl(base, trimmed);
  if (!["http:", "https:"].includes(target.protocol)) {
    throw new Error("代理请求只允许 HTTP/HTTPS 协议");
  }
  if (target.origin !== base.origin) {
    throw new Error("代理请求必须指向当前采集服务");
  }
  const basePath = normalizePath(base.pathname);
  if (target.pathname !== basePath && !target.pathname.startsWith(`${basePath}/`)) {
    throw new Error("代理请求路径不能越过采集服务上下文");
  }

  const query = serializeQueryParams(params);
  if (query) {
    const search = new URLSearchParams(target.search);
    new URLSearchParams(query).forEach((value, key) => search.append(key, value));
    target.search = search.toString();
  }
  return target;
}

/**
 * 只透传安全请求头，并由代理统一注入运维令牌。
 */
export function normalizeProxyHeaders(headers: Record<string, string> = {}, token = ""): Record<string, string> {
  const normalized: Record<string, string> = {};
  Object.entries(headers || {})
    .sort(([left], [right]) => left.localeCompare(right))
    .forEach(([key, value]) => {
      const lowered = key.toLowerCase();
      if (ALLOWED_REQUEST_HEADERS.has(lowered) && value !== undefined && value !== null) {
        normalized[canonicalHeaderName(lowered)] = String(value);
      }
    });
  const trimmedToken = String(token || "").trim();
  if (trimmedToken) {
    normalized["X-Collector-Token"] = trimmedToken;
  }
  return normalized;
}

/**
 * 序列化查询参数，跳过空值，数组按重复 key 输出。
 */
export function serializeQueryParams(params: Record<string, unknown> = {}): string {
  const query = new URLSearchParams();
  Object.entries(params || {}).forEach(([key, value]) => {
    if (value === undefined || value === null || value === "") {
      return;
    }
    if (Array.isArray(value)) {
      value.forEach((item) => {
        if (item !== undefined && item !== null && item !== "") {
          query.append(key, String(item));
        }
      });
      return;
    }
    query.append(key, String(value));
  });
  return query.toString();
}

/**
 * 执行受控 HTTP 代理请求，供 Electron IPC handler 调用。
 */
export async function executeCollectorProxyRequest(request: CollectorProxyRequest): Promise<CollectorProxyResponse> {
  const target = buildCollectorProxyUrl(request.serverUrl, request.url, request.params);
  const method = normalizeMethod(request.method);
  const headers = normalizeProxyHeaders(request.headers, request.token);
  const body = buildRequestBody(request.data, headers);
  const controller = new AbortController();
  const timeout = windowSafeTimeout(request.timeoutMs);
  const timer = setTimeout(() => controller.abort(), timeout);
  try {
    const response = await fetch(target, {
      method,
      headers,
      body: method === "GET" || method === "HEAD" ? undefined : body,
      signal: controller.signal
    });
    const responseText = await response.text();
    const responseHeaders: Record<string, string> = {};
    response.headers.forEach((value, key) => {
      responseHeaders[key] = value;
    });
    return {
      status: response.status,
      statusText: response.statusText,
      headers: responseHeaders,
      body: parseResponseBody(responseText)
    };
  } catch (error) {
    if (error instanceof Error && error.name === "AbortError") {
      throw new Error("请求采集服务超时", { cause: error });
    }
    throw error;
  } finally {
    clearTimeout(timer);
  }
}

function resolveTargetUrl(base: URL, rawUrl: string): URL {
  if (/^[a-z][a-z0-9+.-]*:/i.test(rawUrl)) {
    return new URL(rawUrl);
  }
  const basePath = normalizePath(base.pathname);
  if (rawUrl.startsWith("/")) {
    const path = rawUrl === basePath || rawUrl.startsWith(`${basePath}/`) ? rawUrl : `${basePath}${rawUrl}`;
    return new URL(path, base.origin);
  }
  return new URL(`${basePath}/${rawUrl}`, base.origin);
}

function normalizePath(pathname: string): string {
  const path = `/${String(pathname || "").replace(/^\/+|\/+$/g, "")}`;
  return path === "/" ? "" : path;
}

function canonicalHeaderName(lowered: string): string {
  return lowered === "content-type" ? "Content-Type" : "Accept";
}

function normalizeMethod(method = "GET"): string {
  const normalized = String(method || "GET").trim().toUpperCase();
  return ["GET", "POST", "PUT", "DELETE", "PATCH", "HEAD"].includes(normalized) ? normalized : "GET";
}

function buildRequestBody(data: unknown, headers: Record<string, string>): BodyInit | undefined {
  if (data === undefined || data === null) {
    return undefined;
  }
  if (typeof data === "string") {
    return data;
  }
  if (!headers["Content-Type"]) {
    headers["Content-Type"] = "application/json";
  }
  return JSON.stringify(data);
}

function parseResponseBody(text: string): unknown {
  if (!text) {
    return {};
  }
  try {
    return JSON.parse(text);
  } catch {
    return { message: text.trim() };
  }
}

function windowSafeTimeout(timeoutMs: unknown): number {
  const value = Number(timeoutMs);
  if (!Number.isFinite(value) || value <= 0) {
    return DEFAULT_PROXY_TIMEOUT_MS;
  }
  return Math.min(MAX_PROXY_TIMEOUT_MS, Math.floor(value));
}
