import { describe, expect, it, vi } from "vitest";

import type { AllDeviceRealtimeDataResponse, DeviceRealtimeDataResponse } from "@/types/monitor";
import { loadRealtimeRowsByContext } from "./realtime-load-strategy";

function buildAggregateResponse(deviceCount: number): AllDeviceRealtimeDataResponse {
  return {
    status: "success",
    deviceCount,
    dataCount: deviceCount,
    devices: Array.from({ length: deviceCount }, (_, index) => ({
      status: "success",
      deviceId: `device-${index + 1}`,
      dataCount: 1,
      data: {
        [`point-${index + 1}`]: {
          pointId: `point-${index + 1}`,
          pointName: `点位${index + 1}`,
          value: index + 1
        }
      },
      timestamp: 1000 + index
    })),
    timestamp: 9999
  };
}

describe("realtime-load-strategy", () => {
  it("all N=1 时只发 1 个 aggregate realtime request", async () => {
    const getAllDeviceRealtimeData = vi.fn().mockResolvedValue(buildAggregateResponse(1));
    const getDeviceRealtimeData = vi.fn<(...args: unknown[]) => Promise<DeviceRealtimeDataResponse>>();

    const rows = await loadRealtimeRowsByContext({ mode: "all", deviceId: "" }, {
      getAllDeviceRealtimeData,
      getDeviceRealtimeData
    });

    expect(rows).toHaveLength(1);
    expect(getAllDeviceRealtimeData).toHaveBeenCalledTimes(1);
    expect(getDeviceRealtimeData).not.toHaveBeenCalled();
  });

  it("all N=10 时只发 1 个 aggregate realtime request", async () => {
    const getAllDeviceRealtimeData = vi.fn().mockResolvedValue(buildAggregateResponse(10));
    const getDeviceRealtimeData = vi.fn<(...args: unknown[]) => Promise<DeviceRealtimeDataResponse>>();

    const rows = await loadRealtimeRowsByContext({ mode: "all", deviceId: "" }, {
      getAllDeviceRealtimeData,
      getDeviceRealtimeData
    });

    expect(rows).toHaveLength(10);
    expect(getAllDeviceRealtimeData).toHaveBeenCalledTimes(1);
    expect(getDeviceRealtimeData).not.toHaveBeenCalled();
  });

  it("all N=100 时只发 1 个 aggregate realtime request", async () => {
    const getAllDeviceRealtimeData = vi.fn().mockResolvedValue(buildAggregateResponse(100));
    const getDeviceRealtimeData = vi.fn<(...args: unknown[]) => Promise<DeviceRealtimeDataResponse>>();

    const rows = await loadRealtimeRowsByContext({ mode: "all", deviceId: "" }, {
      getAllDeviceRealtimeData,
      getDeviceRealtimeData
    });

    expect(rows).toHaveLength(100);
    expect(getAllDeviceRealtimeData).toHaveBeenCalledTimes(1);
    expect(getDeviceRealtimeData).not.toHaveBeenCalled();
  });

  it("device mode 仍走单设备 realtime API", async () => {
    const getAllDeviceRealtimeData = vi.fn();
    const getDeviceRealtimeData = vi.fn().mockResolvedValue({
      status: "success",
      deviceId: "device-a",
      dataCount: 1,
      data: {
        "point-1": {
          pointId: "point-1",
          pointName: "温度",
          value: 12
        }
      },
      timestamp: 1000
    } satisfies DeviceRealtimeDataResponse);

    const rows = await loadRealtimeRowsByContext({ mode: "device", deviceId: "device-a" }, {
      getAllDeviceRealtimeData,
      getDeviceRealtimeData
    });

    expect(rows).toEqual([{ deviceId: "device-a", pointId: "point-1", pointName: "温度", value: 12 }]);
    expect(getDeviceRealtimeData).toHaveBeenCalledTimes(1);
    expect(getDeviceRealtimeData).toHaveBeenCalledWith("device-a");
    expect(getAllDeviceRealtimeData).not.toHaveBeenCalled();
  });
});
