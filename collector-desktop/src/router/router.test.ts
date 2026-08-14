import { describe, expect, it } from "vitest";

import { appRouteDefinitions } from "./route-definitions";

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
      "/collect",
      "/cloud",
      "/diagnostic",
      "/log",
      "/network",
      "/control",
      "/shadow"
    ]));
  });
});
