import { describe, expect, it } from "vitest";

import { buildAboutInfo, isSafeExternalUrl, normalizeServerConfig, normalizeWindowState } from "./main-utils.js";

describe("main-utils", () => {
  it("归一化服务地址并补齐 collector context-path", () => {
    expect(normalizeServerConfig({ serverUrl: "http://127.0.0.1:9090/" })).toEqual({ serverUrl: "http://127.0.0.1:9090/collector" });
    expect(normalizeServerConfig({ serverUrl: "http://127.0.0.1:9090/collector/" })).toEqual({ serverUrl: "http://127.0.0.1:9090/collector" });
  });

  it("窗口尺寸不小于工业工作台最小尺寸", () => {
    expect(normalizeWindowState({ width: 800, height: 500 })).toMatchObject({ width: 1180, height: 760 });
    expect(normalizeWindowState({ width: 1600, height: 1000 })).toMatchObject({ width: 1600, height: 1000 });
  });

  it("只允许安全外链协议", () => {
    expect(isSafeExternalUrl("https://hermes-agent.nousresearch.com/docs")).toBe(true);
    expect(isSafeExternalUrl("http://127.0.0.1:9090/collector")).toBe(true);
    expect(isSafeExternalUrl("javascript:alert(1)")).toBe(false);
  });

  it("构造关于信息且明确不管理后端进程", () => {
    expect(buildAboutInfo("0.1.0", "win32")).toContain("v0.1.0");
    expect(buildAboutInfo("0.1.0", "win32")).toContain("不会自动启动 Spring Boot");
  });
});
