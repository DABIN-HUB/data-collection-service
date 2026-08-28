import { describe, expect, it } from "vitest";

import { appRouteDefinitions } from "./route-definitions";
import { resolveLegacyModuleByRoutePath, routePathForLegacyModule } from "./route-names";

describe("router", () => {
  function childRoute(path: string) {
    const shell = appRouteDefinitions.find((route) => route.path === "/");
    return shell?.children?.find((route) => route.path === path);
  }

  it("覆盖原 admin 控制台所有一级功能路由", () => {
    const shell = appRouteDefinitions.find((route) => route.path === "/");
    const paths = shell?.children?.map((route) => `/${route.path}`) || [];
    expect(paths).toEqual(expect.arrayContaining([
      "/dashboard",
      "/realtime",
      "/history",
      "/alarm",
      "/device",
      "/device/workbench",
      "/collect",
      "/cloud",
      "/diagnostic",
      "/log",
      "/network",
      "/control",
      "/shadow"
    ]));
  });

  it("dashboard 直接路由到独立 DashboardView", () => {
    expect(String(childRoute("dashboard")?.component)).toContain("DashboardView.vue");
  });

  it("realtime 直接路由到独立 RealtimeView", () => {
    expect(String(childRoute("realtime")?.component)).toContain("RealtimeView.vue");
  });

  it("log 直接路由到独立 LogView", () => {
    expect(String(childRoute("log")?.component)).toContain("LogView.vue");
  });

  it("alarm 直接路由到独立 AlarmView", () => {
    expect(String(childRoute("alarm")?.component)).toContain("AlarmView.vue");
  });

  it("其它未迁移页面仍保持 LegacyConsoleView 过渡状态", () => {
    for (const path of ["history", "device", "device/workbench", "collect", "cloud", "diagnostic", "network", "control", "shadow"]) {
      expect(String(childRoute(path)?.component)).toContain("LegacyConsoleView.vue");
    }
  });

  it("为旧业务宿主提供单向 route 到模块映射", () => {
    expect(resolveLegacyModuleByRoutePath("/dashboard")).toBe("overview");
    expect(resolveLegacyModuleByRoutePath("/device/workbench")).toBe("workbench");
    expect(resolveLegacyModuleByRoutePath("/control")).toBe("control");
    expect(resolveLegacyModuleByRoutePath("/shadow")).toBe("shadow");
    expect(routePathForLegacyModule("workbench")).toBe("/device/workbench");
  });
});
