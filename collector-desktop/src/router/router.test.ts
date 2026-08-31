import { describe, expect, it } from "vitest";
import { createMemoryHistory, createRouter } from "vue-router";

import { appRouteDefinitions } from "./route-definitions";
import { RouteNames, resolveWorkbenchRouteTab, routePathForWorkbenchTab } from "./route-names";

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

  it("history 直接路由到独立 HistoryView", () => {
    expect(String(childRoute("history")?.component)).toContain("HistoryView.vue");
  });

  it("log 直接路由到独立 LogView", () => {
    expect(String(childRoute("log")?.component)).toContain("LogView.vue");
  });

  it("alarm 直接路由到独立 AlarmView", () => {
    expect(String(childRoute("alarm")?.component)).toContain("AlarmView.vue");
  });

  it("cloud 直接路由到独立 CloudView", () => {
    expect(String(childRoute("cloud")?.component)).toContain("CloudView.vue");
  });

  it("diagnostic 直接路由到独立 DiagnosticView", () => {
    expect(String(childRoute("diagnostic")?.component)).toContain("DiagnosticView.vue");
  });

  it("network 直接路由到独立 NetworkView", () => {
    expect(String(childRoute("network")?.component)).toContain("NetworkView.vue");
  });

  it("collect 直接路由到独立 CollectionView", () => {
    expect(String(childRoute("collect")?.component)).toContain("CollectionView.vue");
  });

  it("device 直接路由到独立 DeviceListView", () => {
    expect(String(childRoute("device")?.component)).toContain("DeviceListView.vue");
  });

  it("device workbench 直接路由到独立 DeviceWorkbenchView", () => {
    expect(String(childRoute("device/workbench")?.component)).toContain("DeviceWorkbenchView.vue");
  });

  it("network route query 可以正常 resolve", () => {
    const router = createRouter({ history: createMemoryHistory(), routes: appRouteDefinitions });
    const resolved = router.resolve("/network?target=127.0.0.1&port=502");
    expect(resolved.name).toBe(RouteNames.NETWORK);
    expect(resolved.query).toMatchObject({ target: "127.0.0.1", port: "502" });
  });

  it("diagnostic route query 可以携带设备上下文", () => {
    const router = createRouter({ history: createMemoryHistory(), routes: appRouteDefinitions });
    const resolved = router.resolve("/diagnostic?deviceId=dev-1");
    expect(resolved.name).toBe(RouteNames.DIAGNOSTIC);
    expect(resolved.query).toMatchObject({ deviceId: "dev-1" });
  });

  it("history route query 可以携带设备和点位上下文", () => {
    const router = createRouter({ history: createMemoryHistory(), routes: appRouteDefinitions });
    const resolved = router.resolve("/history?deviceId=dev-1&pointId=temp-1");
    expect(resolved.name).toBe(RouteNames.HISTORY);
    expect(resolved.query).toMatchObject({ deviceId: "dev-1", pointId: "temp-1" });
  });

  it("collect route query 可以携带设备上下文", () => {
    const router = createRouter({ history: createMemoryHistory(), routes: appRouteDefinitions });
    const resolved = router.resolve("/collect?deviceId=dev-1");
    expect(resolved.name).toBe(RouteNames.COLLECTION);
    expect(resolved.query).toMatchObject({ deviceId: "dev-1" });
  });

  it("device route query 可以携带设备上下文", () => {
    const router = createRouter({ history: createMemoryHistory(), routes: appRouteDefinitions });
    const resolved = router.resolve("/device?deviceId=dev-1");
    expect(resolved.name).toBe(RouteNames.DEVICE);
    expect(resolved.query).toMatchObject({ deviceId: "dev-1" });
  });

  it("device workbench route query 可以携带设备上下文", () => {
    const router = createRouter({ history: createMemoryHistory(), routes: appRouteDefinitions });
    const resolved = router.resolve("/device/workbench?deviceId=dev-1");
    expect(resolved.name).toBe(RouteNames.DEVICE_WORKBENCH);
    expect(String(resolved.matched.at(-1)?.components?.default)).toContain("DeviceWorkbenchView.vue");
    expect(resolved.query).toMatchObject({ deviceId: "dev-1" });
  });

  it("control/shadow route query 仍进入 LegacyConsoleView", () => {
    const router = createRouter({ history: createMemoryHistory(), routes: appRouteDefinitions });
    for (const target of ["/control?deviceId=dev-1", "/shadow?deviceId=dev-1"]) {
      const resolved = router.resolve(target);
      expect(String(resolved.matched.at(-1)?.components?.default)).toContain("LegacyConsoleView.vue");
      expect(resolved.query).toMatchObject({ deviceId: "dev-1" });
    }
  });

  it("其它未迁移页面仍保持 LegacyConsoleView 过渡状态", () => {
    for (const path of ["control", "shadow"]) {
      expect(String(childRoute(path)?.component)).toContain("LegacyConsoleView.vue");
    }
  });

  it("为 Control/Shadow 过渡宿主提供 route 到分区的单向映射", () => {
    expect(resolveWorkbenchRouteTab("/control")).toBe("control");
    expect(resolveWorkbenchRouteTab("/shadow")).toBe("shadow");
    expect(resolveWorkbenchRouteTab("/device/workbench")).toBeNull();
    expect(resolveWorkbenchRouteTab("/dashboard")).toBeNull();
    expect(resolveWorkbenchRouteTab("/device")).toBeNull();
    expect(routePathForWorkbenchTab("config")).toBe("/device/workbench");
    expect(routePathForWorkbenchTab("control")).toBe("/control");
    expect(routePathForWorkbenchTab("shadow")).toBe("/shadow");
  });
});
