import { afterEach, describe, expect, it, vi } from "vitest";

import {
  buildCollectorProxyUrl,
  executeCollectorProxyRequest,
  MAX_PROXY_REQUEST_BODY_BYTES,
  MAX_PROXY_RESPONSE_BODY_BYTES,
  MAX_PROXY_URL_BYTES,
  normalizeProxyHeaders,
  serializeQueryParams,
  withAuthoritativeProxyServerUrl
} from "./http-proxy-utils.js";

const originalFetch = globalThis.fetch;

afterEach(() => {
  globalThis.fetch = originalFetch;
  vi.restoreAllMocks();
});

describe("http-proxy-utils", () => {
  it("把相对接口路径限制在配置的 collector baseUrl 内", () => {
    expect(buildCollectorProxyUrl("http://127.0.0.1:9090/collector", "/api/protocols").toString()).toBe("http://127.0.0.1:9090/collector/api/protocols");
    expect(buildCollectorProxyUrl("http://127.0.0.1:9090/collector", "api/config/devices").toString()).toBe("http://127.0.0.1:9090/collector/api/config/devices");
  });

  it("拒绝越过 collector 上下文或跳转到其他 origin 的代理请求", () => {
    expect(() => buildCollectorProxyUrl("http://127.0.0.1:9090/collector", "http://example.com/api")).toThrow("代理请求必须指向当前采集服务");
    expect(() => buildCollectorProxyUrl("http://127.0.0.1:9090/collector", "../admin/index.html")).toThrow("代理请求路径不能越过采集服务上下文");
    expect(() => buildCollectorProxyUrl("http://127.0.0.1:9090/collector", "javascript:alert(1)")).toThrow("代理请求只允许 HTTP/HTTPS 协议");
  });

  it("限制代理 URL/query 的字节长度", () => {
    expect(() => buildCollectorProxyUrl("http://127.0.0.1:9090/collector", "/api/logs", { q: "x".repeat(MAX_PROXY_URL_BYTES) })).toThrow("代理请求 URL 超过长度限制");
  });

  it("序列化查询参数并跳过空值", () => {
    expect(serializeQueryParams({ level: "WARN", empty: "", limit: 50, missing: undefined })).toBe("level=WARN&limit=50");
  });

  it("只透传安全请求头并注入运维令牌", () => {
    expect(normalizeProxyHeaders({ Cookie: "bad", "Content-Type": "application/json", Accept: "application/json", Authorization: "bad" }, "token-value")).toEqual({
      "Accept": "application/json",
      "Content-Type": "application/json",
      "X-Collector-Token": "token-value"
    });
  });

  it("忽略 renderer 提供的 serverUrl，强制使用 Main authoritative serverUrl", () => {
    const request = withAuthoritativeProxyServerUrl({
      serverUrl: "http://127.0.0.1:1/collector",
      url: "/api/protocols",
      method: "GET"
    }, "http://127.0.0.1:9090/collector");

    expect(request.serverUrl).toBe("http://127.0.0.1:9090/collector");
    expect(buildCollectorProxyUrl(request.serverUrl, request.url).toString()).toBe("http://127.0.0.1:9090/collector/api/protocols");
  });

  it("主进程代理保留包含 data 字段的 RAW DTO 响应体", async () => {
    const body = {
      status: "success",
      deviceId: "device-1",
      dataCount: 1,
      data: {
        "point-1": {
          pointId: "point-1",
          value: 10
        }
      },
      timestamp: 123456
    };
    globalThis.fetch = vi.fn().mockResolvedValue({
      status: 200,
      statusText: "OK",
      headers: new Headers({ "content-type": "application/json" }),
      text: vi.fn().mockResolvedValue(JSON.stringify(body))
    }) as unknown as typeof fetch;

    await expect(executeCollectorProxyRequest({
      serverUrl: "http://127.0.0.1:9090/collector",
      url: "/api/data/device/device-1",
      method: "GET"
    })).resolves.toMatchObject({
      status: 200,
      body
    });
  });

  it("允许当前 realtime 级别的大响应边界但拒绝 Content-Length 超限", async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      status: 200,
      statusText: "OK",
      headers: new Headers({ "content-length": String(MAX_PROXY_RESPONSE_BODY_BYTES + 1) }),
      text: vi.fn().mockResolvedValue("{}")
    }) as unknown as typeof fetch;

    await expect(executeCollectorProxyRequest({
      serverUrl: "http://127.0.0.1:9090/collector",
      url: "/api/data/realtime/compact/all",
      method: "GET"
    })).rejects.toThrow("采集服务响应体超过大小限制");
  });

  it("拒绝 chunked/stream 响应累计超限", async () => {
    const chunk = new Uint8Array(1024 * 1024);
    let count = 0;
    const body = new ReadableStream<Uint8Array>({
      pull(controller) {
        count += 1;
        if (count <= 65) {
          controller.enqueue(chunk);
        } else {
          controller.close();
        }
      }
    });
    globalThis.fetch = vi.fn().mockResolvedValue({
      status: 200,
      statusText: "OK",
      headers: new Headers({ "content-type": "application/json" }),
      body
    }) as unknown as typeof fetch;

    await expect(executeCollectorProxyRequest({
      serverUrl: "http://127.0.0.1:9090/collector",
      url: "/api/data/realtime/compact/all",
      method: "GET"
    })).rejects.toThrow("采集服务响应体超过大小限制");
  });

  it("拒绝超限请求体", async () => {
    await expect(executeCollectorProxyRequest({
      serverUrl: "http://127.0.0.1:9090/collector",
      url: "/api/config/import",
      method: "POST",
      data: "x".repeat(MAX_PROXY_REQUEST_BODY_BYTES + 1)
    })).rejects.toThrow("代理请求体超过大小限制");
  });
});
