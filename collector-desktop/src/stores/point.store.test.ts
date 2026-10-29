import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

import { usePointStore } from "./point.store";
import { ApiRequestError, unwrapApiResponse } from "@/api/http";
import { capturedPhase3Receipt } from "@/features/device/utils/phase3-http-receipts.test-fixtures";
import type { DataPoint } from "@/types/point";

const apiMocks = vi.hoisted(() => ({
  getDeviceConfigBundle: vi.fn(),
  validateDeviceConfigBundle: vi.fn(),
  commitDeviceConfigBundle: vi.fn()
}));

vi.mock("@/api/config.api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/api/config.api")>()),
  getDeviceConfigBundle: apiMocks.getDeviceConfigBundle,
  validateDeviceConfigBundle: apiMocks.validateDeviceConfigBundle,
  commitDeviceConfigBundle: apiMocks.commitDeviceConfigBundle
}));
beforeEach(() => {
  vi.resetAllMocks();
  setActivePinia(createPinia());
  apiMocks.getDeviceConfigBundle.mockResolvedValue({
    points: [],
    configVersion: 1,
    device: { deviceId: "dev-a", deviceName: "测试设备", protocolType: "MODBUS_TCP" },
    connection: { deviceId: "dev-a", host: "127.0.0.1", port: 502 }
  });
  apiMocks.validateDeviceConfigBundle.mockResolvedValue({ valid: true, errors: [] });
  apiMocks.commitDeviceConfigBundle.mockResolvedValue({ deviceId: "dev-a", configVersion: 2 });
});

function createDeferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

async function flushPromises() {
  await Promise.resolve();
  await Promise.resolve();
}

describe("point.store", () => {
  it("同一设备保存尚未返回时再次点击不会并发重放提交", async () => {
    const store = usePointStore();
    await store.load("dev-a");
    const pending = createDeferred<unknown>();
    apiMocks.commitDeviceConfigBundle.mockImplementationOnce(() => pending.promise);
    const first = store.save("dev-a");
    await flushPromises();
    await store.save("dev-a");
    expect(apiMocks.commitDeviceConfigBundle).toHaveBeenCalledTimes(1);
    pending.resolve({ deviceId: "dev-a", configVersion: 2 });
    await first;
  });
  it("503 UNKNOWN 保留草稿/旧版本，禁止重复提交并仅显式读回", async () => {
    const store = usePointStore();
    apiMocks.getDeviceConfigBundle.mockResolvedValueOnce({ configVersion: 4, persistedRevision: 0, device: { deviceId: "dev-a" }, connection: { deviceId: "dev-a" }, points: [point({ pointId: "p1" })] });
    await store.load("dev-a");
    store.updateCell("dev-a", "p1", "pointName", "draft");
    apiMocks.commitDeviceConfigBundle.mockImplementationOnce(async () => unwrapApiResponse(capturedPhase3Receipt("unconfirmed", "dev-a"), 503));
    await store.save("dev-a");
    expect(store.errorFor("dev-a")).toContain("勿重复提交");
    expect(store.getPoints("dev-a")[0].pointName).toBe("draft");
    expect(store.configVersionByDevice["dev-a"]).toBe(4);
    await store.save("dev-a"); await store.load("dev-a");
    expect(apiMocks.commitDeviceConfigBundle).toHaveBeenCalledTimes(1);
    expect(apiMocks.getDeviceConfigBundle).toHaveBeenCalledTimes(1);
    apiMocks.getDeviceConfigBundle.mockResolvedValueOnce({ configVersion: 5, persistedRevision: 1, device: { deviceId: "dev-a" }, connection: { deviceId: "dev-a" }, points: [point({ pointId: "p1", pointName: "authority" })] });
    await store.load("dev-a", true);
    expect(store.getPoints("dev-a")[0].pointName).toBe("authority");
  });
  for (const [code, reconciled] of [["RECONCILED", true], ["RECOVERY_REQUIRED", false], ["APPLY_FAILED", false]] as const) {
    it(`HTTP200 CONFIG_COMMITTED_${code} 不写入缺失版本、不自动读回并防止再次保存`, async () => {
      const store = usePointStore();
      apiMocks.getDeviceConfigBundle.mockResolvedValueOnce({ configVersion: 4, persistedRevision: 0, device: { deviceId: "dev-a" }, connection: { deviceId: "dev-a" }, points: [point({ pointId: "p1" })] });
      await store.load("dev-a");
      store.updateCell("dev-a", "p1", "pointName", "草稿");
      const kind = code === "RECONCILED" ? "reconciled" : code === "RECOVERY_REQUIRED" ? "recoveryRequired" : "applyFailed";
      apiMocks.commitDeviceConfigBundle.mockResolvedValueOnce(unwrapApiResponse(capturedPhase3Receipt(kind, "dev-a"), 200));
      await store.save("dev-a");
      expect(store.getPoints("dev-a")[0].pointName).toBe("草稿");
      expect(store.configVersionByDevice["dev-a"]).toBe(4);
      expect(store.persistedRevisionByDevice["dev-a"]).toBe(0);
      expect(store.errorFor("dev-a")).toContain("显式");
      if (!reconciled) expect(store.errorFor("dev-a")).toContain("配置已提交，运行态待恢复");
      else expect(store.errorFor("dev-a")).toContain("已完成权威对账");
      await store.save("dev-a"); await store.load("dev-a");
      expect(apiMocks.commitDeviceConfigBundle).toHaveBeenCalledTimes(1);
      expect(apiMocks.getDeviceConfigBundle).toHaveBeenCalledTimes(1);
      apiMocks.getDeviceConfigBundle.mockResolvedValueOnce({ configVersion: 5, persistedRevision: 1, device: { deviceId: "dev-a" }, connection: { deviceId: "dev-a" }, points: [point({ pointId: "p1", pointName: "权威" })] });
      await store.load("dev-a", true);
      expect(store.getPoints("dev-a")[0].pointName).toBe("权威");
      expect(store.configVersionByDevice["dev-a"]).toBe(5);
    });
  }

  it("不匹配 deviceIds 的已提交回执不能写版本或刷新草稿", async () => {
    const store = usePointStore();
    await store.load("dev-a");
    apiMocks.commitDeviceConfigBundle.mockResolvedValueOnce({ committed: true, reconciled: true, reloadRequired: true, deviceIds: ["other"] });
    await store.save("dev-a");
    expect(store.errorFor("dev-a")).toContain("身份");
    expect(store.configVersionByDevice["dev-a"]).toBe(1);
    expect(apiMocks.getDeviceConfigBundle).toHaveBeenCalledTimes(1);
  });

  it("普通成功需要匹配 deviceId；正常回执更新真实版本并读回", async () => {
    const store = usePointStore();
    await store.load("dev-a");
    apiMocks.commitDeviceConfigBundle.mockResolvedValueOnce({ deviceId: "other", configVersion: 2, persistedRevision: 1 });
    await store.save("dev-a");
    expect(store.errorFor("dev-a")).toContain("身份");
    expect(store.configVersionByDevice["dev-a"]).toBe(1);
    apiMocks.getDeviceConfigBundle.mockResolvedValueOnce({ configVersion: 3, persistedRevision: 2, device: { deviceId: "dev-a" }, connection: { deviceId: "dev-a" }, points: [] });
    await store.load("dev-a", true);
    apiMocks.commitDeviceConfigBundle.mockResolvedValueOnce(unwrapApiResponse(capturedPhase3Receipt("normal", "dev-a"), 200));
    await store.save("dev-a");
    expect(apiMocks.commitDeviceConfigBundle).toHaveBeenCalledTimes(2);
  });
  it("点位保存只使用读取到的持久修订，不以 JVM configVersion 冒充", async () => {
    const store = usePointStore();
    apiMocks.getDeviceConfigBundle.mockResolvedValueOnce({ configVersion: 4, persistedRevision: 0, device: { deviceId: "dev-a" }, connection: { deviceId: "dev-a" }, points: [point({ pointId: "p1" })] });
    await store.load("dev-a");
    await store.save("dev-a");
    expect(apiMocks.validateDeviceConfigBundle).toHaveBeenCalledWith("dev-a", expect.objectContaining({ baseVersion: 4, baseRevision: 0 }));
    expect(apiMocks.commitDeviceConfigBundle).toHaveBeenCalledWith("dev-a", expect.objectContaining({ baseVersion: 4, baseRevision: 0 }));

    apiMocks.getDeviceConfigBundle.mockResolvedValueOnce({ configVersion: 8, device: { deviceId: "dev-a" }, connection: { deviceId: "dev-a" }, points: [point({ pointId: "p1" })] });
    await store.load("dev-a");
    await store.save("dev-a");
    expect(apiMocks.commitDeviceConfigBundle.mock.calls[1][1]).not.toHaveProperty("baseRevision");
  });

  it("持久修订冲突时点位草稿保持原样，并提示显式重新加载", async () => {
    const store = usePointStore();
    apiMocks.getDeviceConfigBundle.mockResolvedValueOnce({ configVersion: 5, persistedRevision: 2, device: { deviceId: "dev-a" }, connection: { deviceId: "dev-a" }, points: [point({ pointId: "p1" })] });
    await store.load("dev-a");
    store.updateCell("dev-a", "p1", "pointName", "未保存的草稿");
    apiMocks.commitDeviceConfigBundle.mockImplementationOnce(async () => unwrapApiResponse(capturedPhase3Receipt("conflict", "dev-a"), 409));
    await store.save("dev-a");
    expect(store.getPoints("dev-a")[0].pointName).toBe("未保存的草稿");
    expect(store.errorFor("dev-a")).toContain("重新加载");
    expect(apiMocks.getDeviceConfigBundle).toHaveBeenCalledTimes(1);
  });

  it("持久修订冲突后自动挂载读取不能覆盖草稿，显式刷新才接受新配置", async () => {
    const store = usePointStore();
    apiMocks.getDeviceConfigBundle.mockResolvedValueOnce({ configVersion: 5, persistedRevision: 2, device: { deviceId: "dev-a" }, connection: { deviceId: "dev-a" }, points: [point({ pointId: "p1", pointName: "旧值" })] });
    await store.load("dev-a");
    store.updateCell("dev-a", "p1", "pointName", "草稿");
    apiMocks.commitDeviceConfigBundle.mockRejectedValueOnce(new ApiRequestError("冲突", { httpStatus: 409 }));
    await store.save("dev-a");
    await store.load("dev-a");
    expect(store.getPoints("dev-a")[0].pointName).toBe("草稿");
    expect(store.errorFor("dev-a")).toContain("重新加载");
    expect(apiMocks.getDeviceConfigBundle).toHaveBeenCalledTimes(1);

    apiMocks.getDeviceConfigBundle.mockResolvedValueOnce({ configVersion: 6, persistedRevision: 3, device: { deviceId: "dev-a" }, connection: { deviceId: "dev-a" }, points: [point({ pointId: "p1", pointName: "新配置" })] });
    await store.load("dev-a", true);
    expect(store.getPoints("dev-a")[0].pointName).toBe("新配置");
    expect(store.persistedRevisionByDevice["dev-a"]).toBe(3);
  });

  it("提交已持久化但需要恢复时不自动刷新点位、不显示正常应用", async () => {
    const store = usePointStore();
    apiMocks.getDeviceConfigBundle.mockResolvedValueOnce({ configVersion: 5, persistedRevision: 2, device: { deviceId: "dev-a" }, connection: { deviceId: "dev-a" }, points: [point({ pointId: "p1" })] });
    await store.load("dev-a");
    store.updateCell("dev-a", "p1", "pointName", "本地草稿");
    apiMocks.commitDeviceConfigBundle.mockResolvedValueOnce({ committed: true, reconciled: false, reloadRequired: true, deviceIds: ["dev-a"] });
    await store.save("dev-a");
    expect(apiMocks.getDeviceConfigBundle).toHaveBeenCalledTimes(1);
    expect(store.getPoints("dev-a")[0].pointName).toBe("本地草稿");
    expect(store.errorFor("dev-a")).toContain("恢复");
    expect(store.persistedRevisionByDevice["dev-a"]).toBe(2);
  });

  it("新增空点位时写入当前设备并补齐默认字段", () => {
    const store = usePointStore();

    store.addEmptyPoint("dev-1");

    expect(store.getPoints("dev-1")).toHaveLength(1);
    expect(store.getPoints("dev-1")[0]).toMatchObject({
      pointId: expect.stringMatching(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i),
      pointCode: "point_001",
      pointName: "点位001",
      address: "40001",
      dataType: "FLOAT",
      readWrite: "R",
      alarmEnabled: 0,
      status: 1
    });
    expect(store.getPoints("other")).toEqual([]);
  });

  it("追加批量生成点位时保持数值地址递增", () => {
    const store = usePointStore();

    store.appendGeneratedPoints("dev-1", {
      count: 2,
      baseAddress: "40001",
      addressStep: 2,
      pointCodePrefix: "temp",
      pointNamePrefix: "温度",
      dataType: "FLOAT",
      readWrite: "R"
    });

    expect(store.getPoints("dev-1").map((point) => [point.pointCode, point.address])).toEqual([
      ["temp_001", "40001"],
      ["temp_002", "40003"]
    ]);
  });

  it("替换点位时清空当前设备多选状态", () => {
    const store = usePointStore();
    store.setSelectedIds("dev-1", ["p1"]);

    store.replacePoints("dev-1", [{ pointCode: "pressure", pointName: "压力" }]);

    expect(store.getPoints("dev-1")[0]).toMatchObject({
      pointId: expect.stringMatching(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i),
      pointCode: "pressure"
    });
    expect(store.getSelectedIds("dev-1")).toEqual([]);
  });

  it("批量编辑只修改选中点位和显式字段", () => {
    const store = usePointStore();
    store.replacePoints("dev-1", [
      point({ pointId: "p1", pointCode: "temp", unit: "℃", alarmEnabled: 0, dataType: "FLOAT" }),
      point({ pointId: "p2", pointCode: "press", unit: "MPa", alarmEnabled: 0, dataType: "FLOAT" })
    ]);
    store.setSelectedIds("dev-1", ["p1"]);

    store.applyBatch("dev-1", {
      fields: ["alarmEnabled", "unit"],
      values: { alarmEnabled: 1, unit: "K", dataType: "DOUBLE" }
    });

    expect(store.getPoints("dev-1")[0]).toMatchObject({ pointId: "p1", alarmEnabled: 1, unit: "K", dataType: "FLOAT" });
    expect(store.getPoints("dev-1")[1]).toMatchObject({ pointId: "p2", alarmEnabled: 0, unit: "MPa", dataType: "FLOAT" });
  });

  it("删除选中点位时仅影响当前设备并清空选中项", () => {
    const store = usePointStore();
    store.replacePoints("dev-1", [point({ pointId: "p1", pointCode: "temp" }), point({ pointId: "p2", pointCode: "press" })]);
    store.replacePoints("dev-2", [point({ pointId: "p3", pointCode: "speed" })]);
    store.setSelectedIds("dev-1", ["p1"]);

    store.removeSelected("dev-1");

    expect(store.getPoints("dev-1").map((item) => item.pointId)).toEqual(["p2"]);
    expect(store.getSelectedIds("dev-1")).toEqual([]);
    expect(store.getPoints("dev-2").map((item) => item.pointId)).toEqual(["p3"]);
  });

  it("多选状态按设备隔离", () => {
    const store = usePointStore();

    store.setSelectedIds("dev-1", ["p1", "p2"]);
    store.setSelectedIds("dev-2", ["p3"]);

    expect(store.getSelectedIds("dev-1")).toEqual(["p1", "p2"]);
    expect(store.getSelectedIds("dev-2")).toEqual(["p3"]);
  });

  it("同设备 Load1 → Load2 时，旧 Load1 后返回不会覆盖新 Load2", async () => {
    const load1 = createDeferred<{ points: DataPoint[] }>();
    const load2 = createDeferred<{ points: DataPoint[] }>();
    apiMocks.getDeviceConfigBundle
      .mockImplementationOnce(() => load1.promise)
      .mockImplementationOnce(() => load2.promise);
    const store = usePointStore();

    const request1 = store.load("dev-a");
    await flushPromises();
    const request2 = store.load("dev-a");
    await flushPromises();

    load2.resolve({ points: [point({ pointId: "latest", pointCode: "latest" })] });
    await request2;
    load1.resolve({ points: [point({ pointId: "stale", pointCode: "stale" })] });
    await request1;

    expect(store.getPoints("dev-a")[0]).toMatchObject({ pointId: "latest" });
  });

  it("A 与 B 设备 load 并行时，互相独立提交各自 points 和 error", async () => {
    const loadA = createDeferred<{ points: DataPoint[] }>();
    const loadB = createDeferred<{ points: DataPoint[] }>();
    apiMocks.getDeviceConfigBundle
      .mockImplementationOnce(() => loadA.promise)
      .mockImplementationOnce(() => loadB.promise);
    const store = usePointStore();

    const requestA = store.load("dev-a");
    const requestB = store.load("dev-b");
    await flushPromises();

    loadB.resolve({ points: [point({ pointId: "pb", pointCode: "pb" })] });
    await requestB;
    loadA.resolve({ points: [point({ pointId: "pa", pointCode: "pa" })] });
    await requestA;

    expect(store.getPoints("dev-a")[0]).toMatchObject({ pointId: "pa" });
    expect(store.getPoints("dev-b")[0]).toMatchObject({ pointId: "pb" });
    expect(store.errorFor("dev-a")).toBe("");
    expect(store.errorFor("dev-b")).toBe("");
  });

  it("A 设备旧错误不会污染 B 设备 error", async () => {
    const loadA = createDeferred<{ points: DataPoint[] }>();
    const loadB = createDeferred<{ points: DataPoint[] }>();
    apiMocks.getDeviceConfigBundle
      .mockImplementationOnce(() => loadA.promise)
      .mockImplementationOnce(() => loadB.promise);
    const store = usePointStore();

    const requestA = store.load("dev-a");
    const requestB = store.load("dev-b");
    await flushPromises();

    loadB.resolve({ points: [point({ pointId: "pb" })] });
    await requestB;
    loadA.reject(new Error("A 加载失败"));
    await requestA;

    expect(store.errorFor("dev-a")).toBe("A 加载失败");
    expect(store.errorFor("dev-b")).toBe("");
  });

  it("同设备旧 finally 不会关闭 newer loading", async () => {
    const load1 = createDeferred<{ points: DataPoint[] }>();
    const load2 = createDeferred<{ points: DataPoint[] }>();
    apiMocks.getDeviceConfigBundle
      .mockImplementationOnce(() => load1.promise)
      .mockImplementationOnce(() => load2.promise);
    const store = usePointStore();

    const request1 = store.load("dev-a");
    await flushPromises();
    const request2 = store.load("dev-a");
    await flushPromises();

    load1.resolve({ points: [point({ pointId: "stale" })] });
    await request1;

    expect(store.isLoading("dev-a")).toBe(true);

    load2.resolve({ points: [point({ pointId: "latest" })] });
    await request2;

    expect(store.isLoading("dev-a")).toBe(false);
  });

  it("save 会捕获 payload snapshot，并在成功后触发当前设备 reload", async () => {
    const saveRequest = createDeferred<unknown>();
    const reloadRequest = createDeferred<{ points: DataPoint[] }>();
    let capturedPayload: DataPoint[] = [];
    apiMocks.commitDeviceConfigBundle.mockImplementationOnce(async (_deviceId: string, bundle: { points: DataPoint[] }) => {
      capturedPayload = JSON.parse(JSON.stringify(bundle.points));
      return saveRequest.promise;
    });
    apiMocks.getDeviceConfigBundle.mockImplementationOnce(() => reloadRequest.promise);
    const store = usePointStore();
    store.replacePoints("dev-a", [point({ pointId: "p1", pointName: "原始点位" })]);
    store.bundleDeviceByDevice["dev-a"] = { deviceId: "dev-a", deviceName: "测试设备", protocolType: "MODBUS_TCP" };
    store.bundleConnectionByDevice["dev-a"] = { deviceId: "dev-a", host: "127.0.0.1", port: 502 };

    const savePromise = store.save("dev-a");
    await flushPromises();
    store.updateCell("dev-a", "p1", "pointName", "已被本地修改");

    expect(store.isSaving("dev-a")).toBe(true);

    saveRequest.resolve({ deviceId: "dev-a", configVersion: 2 });
    await flushPromises();
    reloadRequest.resolve({ points: [point({ pointId: "reloaded", pointCode: "reloaded" })] });
    await savePromise;

    expect(capturedPayload[0]).toMatchObject({ pointId: "p1", pointName: "原始点位" });
    expect(store.getPoints("dev-a")[0]).toMatchObject({ pointId: "reloaded" });
    expect(store.isSaving("dev-a")).toBe(false);
  });

  it("save pending 时 manual load 先开始，save 完成后的 post-save reload 因更晚开始而成为 latest", async () => {
    const saveRequest = createDeferred<unknown>();
    const manualLoad = createDeferred<{ points: DataPoint[] }>();
    const postSaveLoad = createDeferred<{ points: DataPoint[] }>();
    apiMocks.commitDeviceConfigBundle.mockImplementationOnce(() => saveRequest.promise);
    apiMocks.getDeviceConfigBundle
      .mockImplementationOnce(() => manualLoad.promise)
      .mockImplementationOnce(() => postSaveLoad.promise);
    const store = usePointStore();
    store.replacePoints("dev-a", [point({ pointId: "base" })]);
    store.bundleDeviceByDevice["dev-a"] = { deviceId: "dev-a", deviceName: "测试设备", protocolType: "MODBUS_TCP" };
    store.bundleConnectionByDevice["dev-a"] = { deviceId: "dev-a", host: "127.0.0.1", port: 502 };

    const savePromise = store.save("dev-a");
    await flushPromises();
    const manualLoadPromise = store.load("dev-a");
    await flushPromises();

    saveRequest.resolve({ deviceId: "dev-a", configVersion: 2 });
    await flushPromises();
    manualLoad.resolve({ points: [point({ pointId: "manual-older" })] });
    await manualLoadPromise;

    expect(store.isLoading("dev-a")).toBe(true);

    postSaveLoad.resolve({ points: [point({ pointId: "post-save-latest" })] });
    await savePromise;

    expect(store.getPoints("dev-a")[0]).toMatchObject({ pointId: "post-save-latest" });
  });

  it("save 成功后的 post-save reload 先开始，但随后用户 manual load 开始时，manual load 成为 latest", async () => {
    const postSaveLoad = createDeferred<{ points: DataPoint[] }>();
    const manualLoad = createDeferred<{ points: DataPoint[] }>();
    apiMocks.commitDeviceConfigBundle.mockResolvedValueOnce({ deviceId: "dev-a", configVersion: 2 });
    apiMocks.getDeviceConfigBundle
      .mockImplementationOnce(() => postSaveLoad.promise)
      .mockImplementationOnce(() => manualLoad.promise);
    const store = usePointStore();
    store.replacePoints("dev-a", [point({ pointId: "base" })]);
    store.bundleDeviceByDevice["dev-a"] = { deviceId: "dev-a", deviceName: "测试设备", protocolType: "MODBUS_TCP" };
    store.bundleConnectionByDevice["dev-a"] = { deviceId: "dev-a", host: "127.0.0.1", port: 502 };

    const savePromise = store.save("dev-a");
    await flushPromises();
    const manualLoadPromise = store.load("dev-a");
    await flushPromises();

    postSaveLoad.resolve({ points: [point({ pointId: "post-save-stale" })] });
    await savePromise;

    expect(store.isLoading("dev-a")).toBe(true);

    manualLoad.resolve({ points: [point({ pointId: "manual-latest" })] });
    await manualLoadPromise;

    expect(store.getPoints("dev-a")[0]).toMatchObject({ pointId: "manual-latest" });
    expect(store.isLoading("dev-a")).toBe(false);
  });
});

function point(overrides: Partial<DataPoint>): DataPoint {
  return {
    pointId: "p1",
    pointCode: "point_001",
    pointName: "点位001",
    address: "40001",
    dataType: "FLOAT",
    readWrite: "R",
    alarmEnabled: 0,
    status: 1,
    ...overrides
  };
}