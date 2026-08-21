import { describe, expect, it } from "vitest";

import { buildLocalDevicePayload, buildProtocolPointNotes, extractLocalDeviceBundle, normalizeLocalPoints, validateLocalDeviceDraft } from "./local-device-utils";

describe("local-device-utils", () => {
  it("构造本地临时设备保存 payload", () => {
    const payload = buildLocalDevicePayload({
      deviceId: "local-1",
      deviceName: "本地设备1",
      protocol: "MODBUS_TCP",
      adaptive: { baseCollectionInterval: 1000, minCollectionInterval: 500, maxCollectionInterval: 5000, pointChangeThreshold: 0.01 },
      connection: { host: "127.0.0.1", port: 1502, extJson: { slaveId: 1 } },
      points: [{ pointCode: "temperature", pointName: "温度", address: "40001", dataType: "FLOAT" }],
      cloudTarget: { enabled: false, deviceType: "SUB_DEVICE", topologyEnabled: true },
      overwrite: true,
      startAfterSave: true
    });

    expect(payload.device).toMatchObject({
      id: "local-1",
      deviceName: "本地设备1",
      protocolType: "MODBUS_TCP",
      connectionType: "MODBUS_TCP",
      ipAddress: "127.0.0.1",
      port: 1502,
      collectionInterval: 1000,
      configSource: "local",
      temporaryConfig: true,
      status: "OFFLINE"
    });
    expect(payload.connection).toMatchObject({
      deviceId: "local-1",
      connectionType: "MODBUS_TCP",
      host: "127.0.0.1",
      port: 1502,
      extJson: { slaveId: 1, configSource: "local", temporaryConfig: true }
    });
    expect(payload.points[0]).toMatchObject({
      deviceId: "local-1",
      baseCollectionInterval: 1000,
      currentCollectionInterval: 1000,
      minCollectionInterval: 500,
      maxCollectionInterval: 5000,
      pointChangeThreshold: 0.01,
      additionalConfig: { configSource: "local", temporaryConfig: true }
    });
    expect(payload.overwrite).toBe(true);
    expect(payload.startAfterSave).toBe(true);
  });

  it("规范化点位并补充默认字段", () => {
    const points = normalizeLocalPoints([{ pointCode: "p1", pointName: "点位1", address: "sensor/topic" }], "dev", "MQTT", {
      baseCollectionInterval: 2000,
      minCollectionInterval: 1000,
      maxCollectionInterval: 10000,
      pointChangeThreshold: 0.02
    });

    expect(points[0]).toMatchObject({
      pointId: "local-p1",
      deviceId: "dev",
      dataType: "STRING",
      readWrite: "R",
      collectionMode: "SUBSCRIPTION",
      status: 1,
      cacheEnabled: 1,
      alarmEnabled: 0,
      additionalConfig: { reportEnabled: true, reportField: "p1", topic: "sensor/topic", configSource: "local", temporaryConfig: true }
    });
  });

  it("启用云上报时要求云端产品和设备名称", () => {
    expect(validateLocalDeviceDraft({
      deviceId: "dev",
      deviceName: "设备",
      protocol: "MQTT",
      points: [{ pointCode: "p1", pointName: "点位1", address: "a" }],
      cloudTarget: { enabled: true, deviceType: "SUB_DEVICE", topologyEnabled: true }
    })).toContain("启用云上报时必须填写 productKey 和 deviceName");
  });

  it("从本地设备详情响应提取可回填 bundle", () => {
    const bundle = {
      device: { id: "local-1", deviceName: "旧设备" },
      connection: { host: "127.0.0.1", port: 1502 },
      points: [{ pointCode: "p1", address: "40001" }]
    };

    expect(extractLocalDeviceBundle({ bundle })).toEqual(bundle);
    expect(extractLocalDeviceBundle({ data: { bundle } })).toEqual(bundle);
    expect(extractLocalDeviceBundle(null)).toBeNull();
  });

  it("协议点位提示使用结构化文本，避免拼接 HTML", () => {
    const note = buildProtocolPointNotes("SIEMENS_S7", ["DB1.DBW0", "<script>alert(1)</script>"], 2);

    expect(note.addressHints).toEqual(["DB1.DBW0", "<script>alert(1)</script>"]);
    expect(note.messages.join(" ")).toContain("S7 地址栏支持简写");
    expect(note.messages.join(" ")).not.toContain("<code>");
  });
});
