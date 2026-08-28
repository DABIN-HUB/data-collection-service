import { describe, expect, it } from "vitest";

import { appRouteDefinitions } from "./route-definitions";
import { resolveLegacyModuleByRoutePath, routePathForLegacyModule } from "./route-names";

describe("router", () => {
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

  it("为旧业务宿主提供单向 route 到模块映射", () => {
    expect(resolveLegacyModuleByRoutePath("/dashboard")).toBe("overview");
    expect(resolveLegacyModuleByRoutePath("/device/workbench")).toBe("workbench");
    expect(resolveLegacyModuleByRoutePath("/control")).toBe("control");
    expect(resolveLegacyModuleByRoutePath("/shadow")).toBe("shadow");
    expect(routePathForLegacyModule("workbench")).toBe("/device/workbench");
  });
});
