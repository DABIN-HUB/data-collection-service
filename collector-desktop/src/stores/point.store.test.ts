import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

import { usePointStore } from "./point.store";
import type { DataPoint } from "@/types/point";

const apiMocks = vi.hoisted(() => ({
  getDevicePointConfig: vi.fn(),
  saveDevicePointConfig: vi.fn()
}));

vi.mock("@/api/point.api", () => ({
  getDevicePointConfig: apiMocks.getDevicePointConfig,
  saveDevicePointConfig: apiMocks.saveDevicePointConfig
}));

beforeEach(() => {
  vi.clearAllMocks();
  setActivePinia(createPinia());
  apiMocks.getDevicePointConfig.mockResolvedValue({ points: [] });
  apiMocks.saveDevicePointConfig.mockResolvedValue({});
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
  it("新增空点位时写入当前设备并补齐默认字段", () => {
    const store = usePointStore();

    store.addEmptyPoint("dev-1");

    expect(store.getPoints("dev-1")).toHaveLength(1);
    expect(store.getPoints("dev-1")[0]).toMatchObject({
      pointId: "local-point_001",
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

    expect(store.getPoints("dev-1")[0]).toMatchObject({ pointId: "local-pressure", pointCode: "pressure" });
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
    apiMocks.getDevicePointConfig
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
    apiMocks.getDevicePointConfig
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
    apiMocks.getDevicePointConfig
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
    apiMocks.getDevicePointConfig
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
    apiMocks.saveDevicePointConfig.mockImplementationOnce(async (_deviceId: string, points: DataPoint[]) => {
      capturedPayload = JSON.parse(JSON.stringify(points));
      return saveRequest.promise;
    });
    apiMocks.getDevicePointConfig.mockImplementationOnce(() => reloadRequest.promise);
    const store = usePointStore();
    store.replacePoints("dev-a", [point({ pointId: "p1", pointName: "原始点位" })]);

    const savePromise = store.save("dev-a");
    await flushPromises();
    store.updateCell("dev-a", "p1", "pointName", "已被本地修改");

    expect(store.isSaving("dev-a")).toBe(true);

    saveRequest.resolve({});
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
    apiMocks.saveDevicePointConfig.mockImplementationOnce(() => saveRequest.promise);
    apiMocks.getDevicePointConfig
      .mockImplementationOnce(() => manualLoad.promise)
      .mockImplementationOnce(() => postSaveLoad.promise);
    const store = usePointStore();
    store.replacePoints("dev-a", [point({ pointId: "base" })]);

    const savePromise = store.save("dev-a");
    await flushPromises();
    const manualLoadPromise = store.load("dev-a");
    await flushPromises();

    saveRequest.resolve({});
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
    apiMocks.saveDevicePointConfig.mockResolvedValueOnce({});
    apiMocks.getDevicePointConfig
      .mockImplementationOnce(() => postSaveLoad.promise)
      .mockImplementationOnce(() => manualLoad.promise);
    const store = usePointStore();
    store.replacePoints("dev-a", [point({ pointId: "base" })]);

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