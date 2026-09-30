import { describe, expect, it } from "vitest";

import { buildDeviceListEmptyText } from "./device-list-utils";

describe("device-list-utils", () => {
  it("设备列表空态能区分加载中、筛选为空和鉴权失败", () => {
    expect(buildDeviceListEmptyText({ loading: true, errorMessage: "", hasFilters: false })).toBe("正在加载设备配置...");
    expect(buildDeviceListEmptyText({ loading: false, errorMessage: "接口访问令牌缺失或无效", hasFilters: false })).toContain("请先在顶部运维令牌处保存令牌");
    expect(buildDeviceListEmptyText({ loading: false, errorMessage: "", hasFilters: true })).toBe("没有符合筛选条件的设备");
  });
});
