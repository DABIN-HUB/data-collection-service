import { describe, expect, it } from "vitest";

import { ApiRequestError, DEFAULT_SERVER_URL, normalizeServerUrl, unwrapApiResponse } from "./http";

describe("http", () => {
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
});
