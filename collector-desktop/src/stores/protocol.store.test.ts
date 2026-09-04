import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

import { useProtocolStore } from "./protocol.store";

const apiMocks = vi.hoisted(() => ({
  listProtocols: vi.fn(),
  getProtocolFields: vi.fn()
}));

vi.mock("@/api/protocol.api", () => ({
  listProtocols: apiMocks.listProtocols,
  getProtocolFields: apiMocks.getProtocolFields
}));

beforeEach(() => {
  vi.clearAllMocks();
  setActivePinia(createPinia());
  apiMocks.listProtocols.mockResolvedValue([]);
  apiMocks.getProtocolFields.mockResolvedValue([]);
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

describe("protocol.store lifecycle", () => {
  it("refresh A → B 时，B 返回后 A 后返回不会覆盖协议列表", async () => {
    const refreshA = createDeferred<Array<{ protocol: string }>>();
    const refreshB = createDeferred<Array<{ protocol: string }>>();
    apiMocks.listProtocols
      .mockImplementationOnce(() => refreshA.promise)
      .mockImplementationOnce(() => refreshB.promise);
    const store = useProtocolStore();

    const requestA = store.refresh();
    await flushPromises();
    const requestB = store.refresh();
    await flushPromises();

    refreshB.resolve([{ protocol: "OPCUA" }]);
    await requestB;
    refreshA.resolve([{ protocol: "MODBUS_TCP" }]);
    await requestA;

    expect(store.protocols).toEqual([{ protocol: "OPCUA" }]);
  });

  it("A stale failure 不会覆盖 B 成功或提前关闭 loading", async () => {
    const refreshA = createDeferred<Array<{ protocol: string }>>();
    const refreshB = createDeferred<Array<{ protocol: string }>>();
    apiMocks.listProtocols
      .mockImplementationOnce(() => refreshA.promise)
      .mockImplementationOnce(() => refreshB.promise);
    const store = useProtocolStore();

    const requestA = store.refresh();
    await flushPromises();
    const requestB = store.refresh();
    await flushPromises();

    refreshA.reject(new Error("A failed"));
    await requestA;
    expect(store.loading).toBe(true);

    refreshB.resolve([{ protocol: "OPCUA" }]);
    await requestB;

    expect(store.loading).toBe(false);
    expect(store.error).toBe("");
    expect(store.protocols).toEqual([{ protocol: "OPCUA" }]);
  });

  it("同 protocol 的旧 loadFields 后返回不会覆盖 newer request", async () => {
    const load1 = createDeferred<Array<{ name: string }>>();
    const load2 = createDeferred<Array<{ name: string }>>();
    apiMocks.getProtocolFields
      .mockImplementationOnce(() => load1.promise)
      .mockImplementationOnce(() => load2.promise);
    const store = useProtocolStore();

    const request1 = store.loadFields("MODBUS_TCP");
    await flushPromises();
    const request2 = store.loadFields("MODBUS_TCP");
    await flushPromises();

    load2.resolve([{ name: "latest-field" }]);
    await request2;
    load1.resolve([{ name: "stale-field" }]);
    await request1;

    expect(store.fieldsByProtocol["MODBUS_TCP"]).toEqual([{ name: "latest-field" }]);
  });

  it("不同 protocol 的字段读取彼此独立，不跨 protocol 污染", async () => {
    const modbus = createDeferred<Array<{ name: string }>>();
    const opcua = createDeferred<Array<{ name: string }>>();
    apiMocks.getProtocolFields
      .mockImplementationOnce(() => modbus.promise)
      .mockImplementationOnce(() => opcua.promise);
    const store = useProtocolStore();

    const modbusRequest = store.loadFields("MODBUS_TCP");
    const opcuaRequest = store.loadFields("OPCUA");
    await flushPromises();

    opcua.resolve([{ name: "opcua-field" }]);
    await opcuaRequest;
    modbus.resolve([{ name: "modbus-field" }]);
    await modbusRequest;

    expect(store.fieldsByProtocol["MODBUS_TCP"]).toEqual([{ name: "modbus-field" }]);
    expect(store.fieldsByProtocol["OPCUA"]).toEqual([{ name: "opcua-field" }]);
  });
});
