import { afterEach, describe, expect, it, vi } from "vitest";

import { ApiRequestError, configureHttp, DEFAULT_SERVER_URL, normalizeServerUrl, request, resolveBrowserServerUrl, unwrapApiResponse } from "./http";

const globalWindow = globalThis as unknown as { window?: unknown };
const originalWindow = globalWindow.window;

type DesktopBridge = NonNullable<Window["collectorDesktop"]>;

function installDesktopProxy(proxyRequest: DesktopBridge["request"]): void {
  globalWindow.window = {
    collectorDesktop: {
      getAppInfo: vi.fn().mockResolvedValue({ name: "数据采集工作台", version: "0.1.0", platform: "test" }),
      getServerConfig: vi.fn().mockResolvedValue({ serverUrl: DEFAULT_SERVER_URL }),
      setServerConfig: vi.fn().mockResolvedValue({ serverUrl: DEFAULT_SERVER_URL }),
      request: proxyRequest,
      openExternal: vi.fn().mockResolvedValue(true),
      onNavigate: vi.fn().mockReturnValue(() => undefined)
    }
  };
}

describe("http", () => {
  afterEach(() => {
    globalWindow.window = originalWindow;
    configureHttp({ serverUrl: DEFAULT_SERVER_URL, token: "" });
    vi.restoreAllMocks();
  });

  it("默认连接已启动的本地 collector 服务", () => {
    expect(DEFAULT_SERVER_URL).toBe("http://127.0.0.1:9090/collector");
    expect(normalizeServerUrl("  ")).toBe(DEFAULT_SERVER_URL);
  });

  it("统一裁剪服务地址末尾斜杠", () => {
    expect(normalizeServerUrl("http://127.0.0.1:9090/collector///")).toBe("http://127.0.0.1:9090/collector");
  });

  it("把旧保存的 9090 根地址迁移到 collector 上下文路径", () => {
    expect(normalizeServerUrl("http://127.0.0.1:9090")).toBe("http://127.0.0.1:9090/collector");
  });

  it("浏览器内置网页模式从 /collector/desktop/ 自动推导后端服务地址", () => {
    expect(resolveBrowserServerUrl("http://192.168.1.10:9090/collector/desktop/index.html#/dashboard")).toBe("http://192.168.1.10:9090/collector");
    expect(resolveBrowserServerUrl("http://192.168.1.10:9090/desktop/index.html#/dashboard")).toBe("http://192.168.1.10:9090");
  });

  it("普通开发页面不误用当前 Vite 地址作为后端地址", () => {
    expect(resolveBrowserServerUrl("http://127.0.0.1:5173/#/dashboard")).toBe(DEFAULT_SERVER_URL);
  });

  it("解析 ApiResult 成功响应", () => {
    expect(unwrapApiResponse({ code: 200, message: "成功", data: { ok: true } })).toEqual({ ok: true });
  });

  it("把业务错误转换成包含状态码的异常", () => {
    expect(() => unwrapApiResponse({ code: 403, message: "权限不足" })).toThrow("权限不足");
  });

  it("把 missing credential 转换成中文鉴权提示", () => {
    expect(() => unwrapApiResponse({ status: "error", message: "missing credential" })).toThrow("接口访问令牌缺失或无效");
  });

  it("原始 health 对象即使 status=DOWN 也按原始响应返回", () => {
    expect(unwrapApiResponse({ status: "DOWN", components: { application: { status: "UP" } } })).toEqual({
      status: "DOWN",
      components: { application: { status: "UP" } }
    });
  });

  it("401 错误保留后端响应体便于联调排查", () => {
    try {
      unwrapApiResponse({ status: "error", message: "missing credential" }, 401);
      throw new Error("should throw");
    } catch (error) {
      expect(error).toBeInstanceOf(ApiRequestError);
      expect((error as ApiRequestError).httpStatus).toBe(401);
      expect((error as ApiRequestError).body).toEqual({ status: "error", message: "missing credential" });
    }
  });

  it("Electron 环境优先通过 preload 代理请求后端，避免 file 协议跨域问题", async () => {
    const proxyRequest = vi.fn().mockResolvedValue({
      status: 200,
      statusText: "OK",
      headers: {},
      body: { status: "success", data: { ok: true } }
    });
    installDesktopProxy(proxyRequest);
    configureHttp({ serverUrl: DEFAULT_SERVER_URL, token: "token-value" });

    await expect(request<{ ok: boolean }>({ url: "/api/protocols", method: "GET", params: { limit: 1 } })).resolves.toEqual({ ok: true });
    expect(proxyRequest).toHaveBeenCalledWith(expect.objectContaining({
      serverUrl: DEFAULT_SERVER_URL,
      token: "token-value",
      url: "/api/protocols",
      method: "GET",
      params: { limit: 1 }
    }));
  });

  it("Electron 代理返回鉴权错误时保持中文提示和响应体", async () => {
    const proxyRequest = vi.fn().mockResolvedValue({
      status: 401,
      statusText: "Unauthorized",
      headers: {},
      body: { status: "error", message: "missing credential" }
    });
    installDesktopProxy(proxyRequest);
    configureHttp({ serverUrl: DEFAULT_SERVER_URL, token: "" });

    await expect(request({ url: "/api/protocols", method: "GET" })).rejects.toThrow("接口访问令牌缺失或无效");
  });

  it("Electron 代理返回非 ApiResult 的 HTTP 错误时也不会误判成功", async () => {
    const proxyRequest = vi.fn().mockResolvedValue({
      status: 500,
      statusText: "Internal Server Error",
      headers: {},
      body: { message: "backend boom" }
    });
    installDesktopProxy(proxyRequest);

    await expect(request({ url: "/api/config/devices", method: "GET" })).rejects.toThrow("backend boom");
  });
});
