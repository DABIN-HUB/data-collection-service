import { afterEach, describe, expect, it, vi } from "vitest";

import { buildCollectorProxyUrl, executeCollectorProxyRequest, normalizeProxyHeaders, serializeQueryParams } from "./http-proxy-utils.js";

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

  it("序列化查询参数并跳过空值", () => {
    expect(serializeQueryParams({ level: "WARN", empty: "", limit: 50, missing: undefined })).toBe("level=WARN&limit=50");
  });

  it("只透传安全请求头并注入运维令牌", () => {
    expect(normalizeProxyHeaders({ Cookie: "bad", "Content-Type": "application/json", Accept: "application/json" }, "token-value")).toEqual({
      "Accept": "application/json",
      "Content-Type": "application/json",
      "X-Collector-Token": "token-value"
    });
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
});
