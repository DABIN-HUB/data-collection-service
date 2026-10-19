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

  it("保存时原样保留历史设备身份及 pointId，不通过 trim 改名", () => {
    const deviceId = " legacy/设备-ID ";
    const payload = buildLocalDevicePayload({
      deviceId,
      deviceName: "历史设备",
      protocol: "MODBUS_TCP",
      connection: { host: "127.0.0.1", port: 502 },
      points: [{ pointId: "historical-point-id", pointCode: "temperature", address: "40001" }]
    });
    expect(payload.device.deviceId).toBe(deviceId);
    expect(payload.device.id).toBe(deviceId);
    expect(payload.connection.deviceId).toBe(deviceId);
    expect(payload.points[0]).toMatchObject({ deviceId, pointId: "historical-point-id" });
  });

  it("同编码的新点位身份唯一，改编码和重试保持原身份及历史 id", () => {
    const points = normalizeLocalPoints([{ pointCode: "p1" }, { pointCode: "p1" }], "dev", "MODBUS_TCP");
    expect(points[0].pointId).toMatch(/^[0-9a-f-]{36}$/i);
    expect(points[0].pointId).not.toBe(points[1].pointId);
    const edited = normalizeLocalPoints([{ ...points[0], id: 123, pointCode: "renamed" }], "dev", "MODBUS_TCP");
    expect(edited[0]).toMatchObject({ id: 123, pointId: points[0].pointId });
    expect(() => normalizeLocalPoints([{ pointId: "same" }, { pointId: "same" }], "dev", "MODBUS_TCP")).toThrow("重复");
    expect(() => normalizeLocalPoints([{ deviceId: "other", pointId: "p" }], "dev", "MODBUS_TCP")).toThrow("归属");
  });

  it("规范化点位并补充默认字段", () => {
    const points = normalizeLocalPoints([{ pointCode: "p1", pointName: "点位1", address: "sensor/topic" }], "dev", "MQTT", {
      baseCollectionInterval: 2000,
      minCollectionInterval: 1000,
      maxCollectionInterval: 10000,
      pointChangeThreshold: 0.02
    });

    expect(points[0]).toMatchObject({
      pointId: expect.stringMatching(/^[0-9a-f-]{36}$/i),
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

  it("保存本地设备前把接口回填的告警规则数组转为 JSON 字符串", () => {
    const payload = buildLocalDevicePayload({
      deviceId: "local-alarm",
      deviceName: "告警测试设备",
      protocol: "MODBUS_TCP",
      connection: { host: "127.0.0.1", port: 502 },
      points: [{
        pointCode: "temperature",
        pointName: "温度",
        address: "40001",
        dataType: "INT",
        alarmRule: [{ ruleId: "r1", operator: ">=", threshold: 10, enabled: true }] as unknown as string
      }]
    });

    expect(typeof payload.points[0].alarmRule).toBe("string");
    expect(JSON.parse(payload.points[0].alarmRule || "[]")).toEqual([{ ruleId: "r1", operator: ">=", threshold: 10, enabled: true }]);
  });

  it("保持点位 adaptive、云上报、连接 extJson 和本地配置来源语义", () => {
    const payload = buildLocalDevicePayload({
      deviceId: "local-cloud",
      deviceName: "云上报测试设备",
      protocol: "MQTT",
      adaptive: { baseCollectionInterval: 3000, minCollectionInterval: 1000, maxCollectionInterval: 6000, pointChangeThreshold: 0.05 },
      connection: { host: "127.0.0.1", port: 1883, extJson: { clientId: "local-cloud" } },
      cloudTarget: { enabled: true, deviceType: "SUB_DEVICE", productKey: "pk", deviceName: "dn", topologyEnabled: false },
      points: [{
        pointCode: "temperature",
        pointName: "温度",
        address: "factory/temperature",
        additionalConfig: {
          reportField: "temperature",
          reportEnabled: true,
          eventEnabled: true,
          streamEnabled: true,
          historyEnabled: true
        }
      }]
    });

    expect(payload.device.cloudTarget).toEqual({ enabled: true, deviceType: "SUB_DEVICE", productKey: "pk", deviceName: "dn", topologyEnabled: false });
    expect(payload.connection.extJson).toEqual({ clientId: "local-cloud", configSource: "local", temporaryConfig: true });
    expect(payload.points[0]).toMatchObject({
      deviceId: "local-cloud",
      dataType: "STRING",
      collectionMode: "SUBSCRIPTION",
      baseCollectionInterval: 3000,
      currentCollectionInterval: 3000,
      minCollectionInterval: 1000,
      maxCollectionInterval: 6000,
      pointChangeThreshold: 0.05,
      additionalConfig: {
        reportField: "temperature",
        reportEnabled: true,
        eventEnabled: true,
        streamEnabled: true,
        historyEnabled: true,
        configSource: "local",
        temporaryConfig: true,
        topic: "factory/temperature"
      }
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
