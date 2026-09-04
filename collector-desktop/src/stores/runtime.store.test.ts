import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

import { useRuntimeStore } from "./runtime.store";

const apiMocks = vi.hoisted(() => ({
  getHealth: vi.fn(),
  getRuntimeStatus: vi.fn()
}));

vi.mock("@/api/runtime.api", () => ({
  getHealth: apiMocks.getHealth,
  getRuntimeStatus: apiMocks.getRuntimeStatus
}));

beforeEach(() => {
  vi.clearAllMocks();
  setActivePinia(createPinia());
  apiMocks.getHealth.mockResolvedValue({ status: "UP", level: "OK" });
  apiMocks.getRuntimeStatus.mockResolvedValue({ level: "OK", message: "runtime ok" });
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

describe("runtime.store lifecycle", () => {
  it("refresh A → B 时，A late response 不会覆盖 B", async () => {
    const healthA = createDeferred<{ status: string; level: string }>();
    const runtimeA = createDeferred<{ level: string; message: string }>();
    const healthB = createDeferred<{ status: string; level: string }>();
    const runtimeB = createDeferred<{ level: string; message: string }>();
    apiMocks.getHealth
      .mockImplementationOnce(() => healthA.promise)
      .mockImplementationOnce(() => healthB.promise);
    apiMocks.getRuntimeStatus
      .mockImplementationOnce(() => runtimeA.promise)
      .mockImplementationOnce(() => runtimeB.promise);
    const store = useRuntimeStore();

    const refreshA = store.refresh();
    await flushPromises();
    const refreshB = store.refresh();
    await flushPromises();

    healthB.resolve({ status: "UP", level: "OK" });
    runtimeB.resolve({ level: "OK", message: "runtime-b" });
    await refreshB;

    healthA.resolve({ status: "DOWN", level: "ERROR" });
    runtimeA.resolve({ level: "ERROR", message: "runtime-a" });
    await refreshA;

    expect(store.health).toMatchObject({ status: "UP", level: "OK" });
    expect(store.runtime).toMatchObject({ message: "runtime-b" });
    expect(store.connected).toBe(true);
  });

  it("A stale failure 不会覆盖 B success，且 A finally 不会关闭 B loading", async () => {
    const healthA = createDeferred<{ status: string; level: string }>();
    const runtimeA = createDeferred<{ level: string; message: string }>();
    const healthB = createDeferred<{ status: string; level: string }>();
    const runtimeB = createDeferred<{ level: string; message: string }>();
    apiMocks.getHealth
      .mockImplementationOnce(() => healthA.promise)
      .mockImplementationOnce(() => healthB.promise);
    apiMocks.getRuntimeStatus
      .mockImplementationOnce(() => runtimeA.promise)
      .mockImplementationOnce(() => runtimeB.promise);
    const store = useRuntimeStore();

    const refreshA = store.refresh();
    await flushPromises();
    const refreshB = store.refresh();
    await flushPromises();

    healthA.reject(new Error("health-a failed"));
    runtimeA.reject(new Error("runtime-a failed"));
    await refreshA;
    expect(store.loading).toBe(true);

    healthB.resolve({ status: "UP", level: "OK" });
    runtimeB.resolve({ level: "OK", message: "runtime-b" });
    await refreshB;

    expect(store.loading).toBe(false);
    expect(store.error).toBe("");
    expect(store.runtime).toMatchObject({ message: "runtime-b" });
  });

  it("保留 partial success semantics：单项成功时 connected 仍为 true", async () => {
    apiMocks.getHealth.mockResolvedValueOnce({ status: "UP", level: "OK" });
    apiMocks.getRuntimeStatus.mockRejectedValueOnce(new Error("runtime failed"));
    const store = useRuntimeStore();

    await store.refresh();

    expect(store.connected).toBe(true);
    expect(store.error).toBe("");
    expect(store.health).toMatchObject({ status: "UP" });
  });
});
