import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("./http", () => ({ requestApiData: vi.fn() }));
import { requestApiData } from "./http";
import { getAlarmLifecycles } from "./alarm.api";

const request = vi.mocked(requestApiData);

describe("告警生命周期 API", () => {
  beforeEach(() => request.mockReset());

  it("按设备、点位、规则、级别和状态查询触发告警", async () => {
    const response = { status: "success" as const, items: [], count: 0 };
    request.mockResolvedValueOnce(response);
    const params = { deviceId: "dev 1", pointId: "p-1", pointCode: "code", ruleId: "r-1",
      level: "WARNING", state: "ACKED" as const, limit: 200 };
    await expect(getAlarmLifecycles(params)).resolves.toEqual(response);
    expect(request).toHaveBeenCalledWith({ url: "/api/alarms", method: "GET", params });
  });
});
