// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createApp, defineComponent, h, nextTick, reactive } from "vue";
import { createPinia, setActivePinia } from "pinia";
import { useDeviceStore } from "@/stores/device.store";
import DeviceListView from "@/views/device/DeviceListView.vue";
import DeviceConfigPanel from "@/components/device/DeviceConfigPanel.vue";
import DeviceRuntimePanel from "@/features/diagnostic/components/DeviceRuntimePanel.vue";

const uiMocks = vi.hoisted(() => ({
  local: vi.fn(), bundle: vi.fn(), validate: vi.fn(), commit: vi.fn(), configDevices: vi.fn(), runtime: vi.fn(), snapshot: vi.fn(),
  status: vi.fn(), running: vi.fn(), runningIds: vi.fn(), protocol: vi.fn(), realtime: vi.fn(), success: vi.fn(), error: vi.fn(), confirm: vi.fn()
}));
vi.mock("@/api/config.api", () => ({
  getLocalDevice: uiMocks.local, getDeviceConfigBundle: uiMocks.bundle, validateDeviceConfigBundle: uiMocks.validate,
  commitDeviceConfigBundle: uiMocks.commit, getDeviceDiff: vi.fn().mockResolvedValue({}),
  deleteLocalDevice: vi.fn(), triggerFullConfigSync: vi.fn(), clearDeviceConfig: vi.fn(), refreshDeviceConfig: vi.fn(), exportConfigs: vi.fn(), importConfigs: vi.fn()
}));
vi.mock("@/api/device.api", () => ({
  getConfigDevices: uiMocks.configDevices, getDeviceRuntime: uiMocks.runtime, getDeviceRuntimeSnapshot: uiMocks.snapshot,
  getDeviceStatus: uiMocks.status, getRunningDevices: uiMocks.runningIds, isDeviceRunning: uiMocks.running,
  startDevice: vi.fn(), startLocalDevice: vi.fn(), stopDevice: vi.fn(), reloadDevices: vi.fn()
}));
vi.mock("@/api/protocol.api", () => ({ getProtocol: uiMocks.protocol }));
vi.mock("@/api/data.api", () => ({ getDeviceRealtimeData: uiMocks.realtime }));
vi.mock("@/stores/app.store", () => ({ useAppStore: () => ({ initialize: vi.fn().mockResolvedValue(undefined) }) }));
vi.mock("@/stores/protocol.store", () => ({ useProtocolStore: () => ({ protocols: [], refresh: vi.fn().mockResolvedValue(undefined) }) }));
vi.mock("vue-router", () => ({ useRoute: () => ({ query: {} }), useRouter: () => ({ push: vi.fn().mockResolvedValue(undefined) }) }));
vi.mock("element-plus", () => ({ ElMessage: { success: uiMocks.success, error: uiMocks.error, warning: vi.fn(), info: vi.fn() }, ElMessageBox: { confirm: uiMocks.confirm } }));
vi.mock("@/features/device/components/LocalDeviceEditor.vue", () => ({ default: defineComponent({ props: ["modelValue", "editingBundle"], setup: (props) => () => props.modelValue ? h("div", { class: "test-editor" }, JSON.stringify(props.editingBundle)) : null }) }));
vi.mock("@/features/point/components/PointEditor.vue", () => ({ default: defineComponent({ render: () => null }) }));
vi.mock("@/components/realtime/RealtimeDataPanel.vue", () => ({ default: defineComponent({ render: () => null }) }));
vi.mock("@/components/alarm/AlarmTablePanel.vue", () => ({ default: defineComponent({ render: () => null }) }));
vi.mock("@/components/log/LogPanel.vue", () => ({ default: defineComponent({ render: () => null }) }));
vi.mock("@/components/protocol/ProtocolDynamicForm.vue", () => ({ default: defineComponent({ render: () => null }) }));

async function settleUi() { await nextTick(); await new Promise(resolve => setTimeout(resolve, 0)); await nextTick(); }
function mountUi(component: Parameters<typeof createApp>[0], props: Record<string, unknown> = {}) {
  const root = document.createElement("div");
  document.body.append(root);
  const app = createApp(component, props);
  app.use(createPinia());
  const passthrough = defineComponent({ setup: (_, { slots, attrs }) => () => h("div", attrs, slots.default?.()) });
  app.component("el-button", defineComponent({ setup: (_, { slots, attrs }) => () => h("button", attrs, slots.default?.()) }));
  for (const name of ["el-alert", "el-tag", "el-table", "el-pagination", "el-dialog", "el-empty"]) app.component(name, passthrough);
  app.component("el-table-column", defineComponent({ render: () => null }));
  app.directive("loading", {});
  app.mount(root);
  return { root, unmount: () => { app.unmount(); root.remove(); } };
}

beforeEach(() => {
  vi.clearAllMocks();
  setActivePinia(createPinia());
  uiMocks.configDevices.mockResolvedValue({ devices: [{ deviceId: "a", temporaryConfig: true }, { deviceId: "b", temporaryConfig: true }] });
  uiMocks.runtime.mockResolvedValue([]);
  uiMocks.snapshot.mockResolvedValue({ deviceId: "a", phase: "ONLINE", ready: true, connected: true });
  uiMocks.status.mockResolvedValue({ deviceId: "a", isRunning: true });
  uiMocks.running.mockResolvedValue(true);
  uiMocks.runningIds.mockResolvedValue(["a"]);
  uiMocks.protocol.mockResolvedValue({ connectionFields: [] });
  uiMocks.realtime.mockResolvedValue([]);
  uiMocks.bundle.mockImplementation((id: string) => Promise.resolve({ configVersion: 1, device: { deviceId: id }, connection: {}, points: [] }));
  uiMocks.validate.mockResolvedValue({ valid: true });
  uiMocks.commit.mockResolvedValue({ configVersion: 2 });
  uiMocks.confirm.mockResolvedValue("confirm");
});


import { createLatestRequestOwner } from "@/features/request/utils/latest-request-owner";
import { shouldClearLastGoodForRequest } from "@/features/request/utils/context-last-good";
import {
  buildDeviceProtocolRequestContext,
  buildDeviceRequestContext,
  isSameDeviceProtocolRequestContext,
  isSameDeviceRequestContext,
  shouldCommitDeviceProtocolSave,
  type DeviceProtocolRequestContext,
  type DeviceRequestContext
} from "@/features/device/utils/device-request-lifecycle";

interface DeviceProtocolHarnessState {
  loading: boolean;
  schema: string | null;
  connection: string | null;
  error: string | null;
}

interface DeviceWorkbenchHarnessState {
  loading: boolean;
  rows: string[];
  selected: string | null;
  page: number;
  error: string | null;
}

interface DeviceDiffHarnessState {
  visible: boolean;
  text: string;
  error: string | null;
}

interface DevicePreviewHarnessState {
  rows: string[];
  error: string | null;
  lastSuccessfulContext: DeviceRequestContext | null;
}

function createDeferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

function createDeviceProtocolHarness(initialContext: DeviceProtocolRequestContext) {
  const owner = createLatestRequestOwner(isSameDeviceProtocolRequestContext);
  const live = { current: { ...initialContext } };
  const state: DeviceProtocolHarnessState = {
    loading: false,
    schema: null,
    connection: null,
    error: null
  };

  async function load(request: Promise<{ schema: string; connection: string }>, snapshot: DeviceProtocolRequestContext) {
    const requestContext = buildDeviceProtocolRequestContext(snapshot.deviceId, snapshot.protocolKey);
    const ticket = owner.begin(requestContext);
    state.loading = true;
    state.error = null;
    try {
      const result = await request;
      if (!owner.canCommit(ticket, buildDeviceProtocolRequestContext(live.current.deviceId, live.current.protocolKey))) {
        return;
      }
      state.schema = result.schema;
      state.connection = result.connection;
    } catch (error) {
      if (!owner.canCommit(ticket, buildDeviceProtocolRequestContext(live.current.deviceId, live.current.protocolKey))) {
        return;
      }
      state.error = error instanceof Error ? error.message : String(error || "协议连接配置加载失败");
    } finally {
      if (owner.isLatest(ticket)) {
        state.loading = false;
      }
    }
  }

  return { live, state, load, invalidate: () => owner.invalidate() };
}

function createDeviceStatusHarness(initialContext: DeviceRequestContext) {
  const owner = createLatestRequestOwner(isSameDeviceRequestContext);
  const live = { current: { ...initialContext } };
  const state = {
    loading: false,
    detail: null as string | null,
    error: null as string | null
  };

  async function load(request: Promise<string>, snapshot: DeviceRequestContext) {
    const requestContext = buildDeviceRequestContext(snapshot.deviceId);
    const ticket = owner.begin(requestContext);
    state.loading = true;
    state.error = null;
    try {
      const result = await request;
      if (!owner.canCommit(ticket, buildDeviceRequestContext(live.current.deviceId))) {
        return;
      }
      state.detail = result;
    } catch (error) {
      if (!owner.canCommit(ticket, buildDeviceRequestContext(live.current.deviceId))) {
        return;
      }
      state.error = error instanceof Error ? error.message : String(error || "连接状态检查失败");
    } finally {
      if (owner.isLatest(ticket)) {
        state.loading = false;
      }
    }
  }

  return { live, state, load, invalidate: () => owner.invalidate() };
}

function createDeviceWorkbenchHarness(initialContext: DeviceRequestContext) {
  const owner = createLatestRequestOwner(isSameDeviceRequestContext);
  const live = { current: { ...initialContext } };
  const state: DeviceWorkbenchHarnessState = {
    loading: false,
    rows: [],
    selected: null,
    page: 1,
    error: null
  };

  async function load(request: Promise<{ rows: string[]; selected: string | null; page: number }>, snapshot: DeviceRequestContext) {
    const requestContext = buildDeviceRequestContext(snapshot.deviceId);
    const ticket = owner.begin(requestContext);
    state.loading = true;
    state.error = null;
    try {
      const result = await request;
      if (!owner.canCommit(ticket, buildDeviceRequestContext(live.current.deviceId))) {
        return;
      }
      state.rows = result.rows;
      state.selected = result.selected;
      state.page = result.page;
    } catch (error) {
      if (!owner.canCommit(ticket, buildDeviceRequestContext(live.current.deviceId))) {
        return;
      }
      state.error = error instanceof Error ? error.message : String(error || "点位运行数据加载失败");
    } finally {
      if (owner.isLatest(ticket)) {
        state.loading = false;
      }
    }
  }

  return { live, state, load, invalidate: () => owner.invalidate() };
}

function createDeviceDiffHarness(initialContext: DeviceRequestContext) {
  const owner = createLatestRequestOwner(isSameDeviceRequestContext);
  const live = { current: { ...initialContext } };
  const state: DeviceDiffHarnessState = {
    visible: false,
    text: "{}",
    error: null
  };

  async function load(request: Promise<string>, snapshot: DeviceRequestContext) {
    const requestContext = buildDeviceRequestContext(snapshot.deviceId);
    const ticket = owner.begin(requestContext);
    state.error = null;
    try {
      const result = await request;
      if (!owner.canCommit(ticket, buildDeviceRequestContext(live.current.deviceId))) {
        return;
      }
      state.text = result;
      state.visible = true;
    } catch (error) {
      if (!owner.canCommit(ticket, buildDeviceRequestContext(live.current.deviceId))) {
        return;
      }
      state.error = error instanceof Error ? error.message : String(error || "配置差异加载失败");
    }
  }

  return {
    live,
    state,
    load,
    switchDevice(deviceId: string) {
      live.current = buildDeviceRequestContext(deviceId);
      owner.invalidate();
      state.visible = false;
      state.text = "{}";
    },
    invalidate: () => owner.invalidate()
  };
}

function createDevicePreviewHarness(initialContext: DeviceRequestContext) {
  const owner = createLatestRequestOwner(isSameDeviceRequestContext);
  const live = { current: { ...initialContext } };
  const state: DevicePreviewHarnessState = {
    rows: [],
    error: null,
    lastSuccessfulContext: null
  };

  async function load(request: Promise<string[]>, snapshot: DeviceRequestContext) {
    const requestContext = buildDeviceRequestContext(snapshot.deviceId);
    const ticket = owner.begin(requestContext);
    if (shouldClearLastGoodForRequest(state.lastSuccessfulContext, requestContext, isSameDeviceRequestContext)) {
      state.rows = [];
      state.lastSuccessfulContext = null;
    }
    state.error = null;
    try {
      const result = await request;
      if (!owner.canCommit(ticket, buildDeviceRequestContext(live.current.deviceId))) {
        return;
      }
      state.rows = result;
      state.lastSuccessfulContext = requestContext;
    } catch (caught) {
      if (!owner.canCommit(ticket, buildDeviceRequestContext(live.current.deviceId))) {
        return;
      }
      state.error = caught instanceof Error ? caught.message : "preview failed";
      if (shouldClearLastGoodForRequest(state.lastSuccessfulContext, requestContext, isSameDeviceRequestContext)) {
        state.rows = [];
      }
    }
  }

  return { live, state, load, invalidate: () => owner.invalidate() };
}

async function flushPromises() {
  await Promise.resolve();
  await Promise.resolve();
}

describe("设备页面真实交错回归", () => {
  it("列表先编辑 A 后编辑 B，A 迟到不能重开覆盖 B 编辑会话", async () => {
    const a = createDeferred<unknown>();
    uiMocks.local.mockImplementationOnce(() => a.promise).mockResolvedValueOnce({ device: { deviceId: "b" }, connection: {}, points: [] });
    const page = mountUi(DeviceListView);
    await settleUi();
    const buttons = [...page.root.querySelectorAll("button")].filter(b => b.textContent === "编辑");
    buttons[0]?.click();
    buttons[1]?.click();
    await settleUi();
    a.resolve({ device: { deviceId: "a" }, connection: {}, points: [] });
    await settleUi();
    expect(page.root.querySelector(".test-editor")?.textContent).toContain('"deviceId":"b"');
    page.unmount();
  });

  it("工作台保存 A 期间切 B 再回 A，旧会话不能覆盖重读配置", async () => {
    const pending = createDeferred<{ valid: boolean }>();
    uiMocks.validate.mockImplementationOnce(() => pending.promise);
    const state = reactive({ device: { normalizedId: "a", deviceId: "a", displayName: "A", displayGroup: "", displayProtocol: "MODBUS_TCP", protocolType: "MODBUS_TCP" } });
    const page = mountUi(defineComponent({ setup: () => () => h(DeviceConfigPanel, { device: state.device }) }));
    await settleUi();
    [...page.root.querySelectorAll("button")].find(b => b.textContent === "保存协议配置")?.click();
    await settleUi();
    expect(useDeviceStore().isDeviceOperating("a")).toBe(true);
    state.device = { ...state.device, normalizedId: "b", deviceId: "b" };
    await settleUi();
    uiMocks.bundle.mockResolvedValueOnce({ configVersion: 9, device: { deviceId: "a" }, connection: {}, points: [] });
    state.device = { ...state.device, normalizedId: "a", deviceId: "a" };
    await settleUi();
    pending.resolve({ valid: true });
    await settleUi();
    expect(uiMocks.commit).toHaveBeenCalledWith("a", expect.objectContaining({ baseVersion: 1, device: expect.objectContaining({ deviceId: "a" }) }));
    expect(page.root.textContent).not.toContain("配置版本 v2");
    page.unmount();
  });

  it("诊断 runtime 查询失败但 running IDs 成功时不能补造已连接", async () => {
    uiMocks.runtime.mockRejectedValueOnce(new Error("运行快照不可用"));
    const page = mountUi(DeviceRuntimePanel, { devices: [{ deviceId: "a" }] });
    await settleUi();
    expect(page.root.textContent).toContain("运行快照不可用");
    expect(page.root.querySelector("tbody")?.textContent).not.toContain("已连接");
    page.unmount();
  });
});

describe("device-request-lifecycle", () => {
  it("same-protocol device switch 时，A 和 B 的 protocol context 仍然不同", () => {
    const deviceA = buildDeviceProtocolRequestContext("device-a", "MODBUS_TCP");
    const deviceB = buildDeviceProtocolRequestContext("device-b", "MODBUS_TCP");

    expect(isSameDeviceProtocolRequestContext(deviceA, deviceB)).toBe(false);
  });

  it("protocol config A → B 且协议相同，B 返回后 A 返回不会覆盖 B", async () => {
    const harness = createDeviceProtocolHarness(buildDeviceProtocolRequestContext("device-a", "MODBUS_TCP"));
    const requestA = createDeferred<{ schema: string; connection: string }>();
    const requestB = createDeferred<{ schema: string; connection: string }>();

    void harness.load(requestA.promise, buildDeviceProtocolRequestContext("device-a", "MODBUS_TCP"));
    await flushPromises();

    harness.live.current = buildDeviceProtocolRequestContext("device-b", "MODBUS_TCP");
    void harness.load(requestB.promise, buildDeviceProtocolRequestContext("device-b", "MODBUS_TCP"));
    await flushPromises();

    requestB.resolve({ schema: "schema-b", connection: "connection-b" });
    await flushPromises();
    requestA.resolve({ schema: "schema-a", connection: "connection-a" });
    await flushPromises();

    expect(harness.state.schema).toBe("schema-b");
    expect(harness.state.connection).toBe("connection-b");
    expect(harness.state.loading).toBe(false);
  });

  it("status owner 与 protocol owner 独立，protocol 新请求不会使 status ticket 失效", () => {
    const protocolOwner = createLatestRequestOwner(isSameDeviceProtocolRequestContext);
    const statusOwner = createLatestRequestOwner(isSameDeviceRequestContext);
    const workbenchOwner = createLatestRequestOwner(isSameDeviceRequestContext);

    const statusTicket = statusOwner.begin(buildDeviceRequestContext("device-a"));
    const workbenchTicket = workbenchOwner.begin(buildDeviceRequestContext("device-a"));
    protocolOwner.begin(buildDeviceProtocolRequestContext("device-a", "MODBUS_TCP"));
    protocolOwner.begin(buildDeviceProtocolRequestContext("device-b", "MODBUS_TCP"));

    expect(statusOwner.canCommit(statusTicket, buildDeviceRequestContext("device-a"))).toBe(true);
    expect(workbenchOwner.canCommit(workbenchTicket, buildDeviceRequestContext("device-a"))).toBe(true);
  });

  it("status A → B 时，A 后返回不会写到 B", async () => {
    const harness = createDeviceStatusHarness(buildDeviceRequestContext("device-a"));
    const requestA = createDeferred<string>();
    const requestB = createDeferred<string>();

    void harness.load(requestA.promise, buildDeviceRequestContext("device-a"));
    await flushPromises();

    harness.live.current = buildDeviceRequestContext("device-b");
    void harness.load(requestB.promise, buildDeviceRequestContext("device-b"));
    await flushPromises();

    requestB.resolve("status-b");
    await flushPromises();
    requestA.resolve("status-a");
    await flushPromises();

    expect(harness.state.detail).toBe("status-b");
    expect(harness.state.loading).toBe(false);
  });

  it("workbench rows A → B 时，A 后返回不会改变 B 的 rows/selection/page", async () => {
    const harness = createDeviceWorkbenchHarness(buildDeviceRequestContext("device-a"));
    const requestA = createDeferred<{ rows: string[]; selected: string | null; page: number }>();
    const requestB = createDeferred<{ rows: string[]; selected: string | null; page: number }>();

    void harness.load(requestA.promise, buildDeviceRequestContext("device-a"));
    await flushPromises();

    harness.live.current = buildDeviceRequestContext("device-b");
    void harness.load(requestB.promise, buildDeviceRequestContext("device-b"));
    await flushPromises();

    requestB.resolve({ rows: ["b-1", "b-2"], selected: "b-2", page: 1 });
    await flushPromises();
    requestA.resolve({ rows: ["a-1"], selected: "a-1", page: 3 });
    await flushPromises();

    expect(harness.state.rows).toEqual(["b-1", "b-2"]);
    expect(harness.state.selected).toBe("b-2");
    expect(harness.state.page).toBe(1);
    expect(harness.state.loading).toBe(false);
  });

  it("showDiff A pending 时切到 B，A resolve 后不会打开 A diff", async () => {
    const harness = createDeviceDiffHarness(buildDeviceRequestContext("device-a"));
    const requestA = createDeferred<string>();

    void harness.load(requestA.promise, buildDeviceRequestContext("device-a"));
    await flushPromises();

    harness.switchDevice("device-b");
    requestA.resolve("diff-a");
    await flushPromises();

    expect(harness.state.visible).toBe(false);
    expect(harness.state.text).toBe("{}");
    expect(harness.state.error).toBeNull();
  });

  it("save target snapshot 正确；保存 A 后切 B 时不把 A state 写进 B", () => {
    const target = buildDeviceProtocolRequestContext("device-a", "MODBUS_TCP");
    const liveB = buildDeviceProtocolRequestContext("device-b", "MODBUS_TCP");

    expect(shouldCommitDeviceProtocolSave(target, target)).toBe(true);
    expect(shouldCommitDeviceProtocolSave(target, liveB)).toBe(false);
  });

  it("preview A → B 时，B resolve 后 A later resolve 不会覆盖 B", async () => {
    const harness = createDevicePreviewHarness(buildDeviceRequestContext("device-a"));
    const requestA = createDeferred<string[]>();
    const requestB = createDeferred<string[]>();

    void harness.load(requestA.promise, buildDeviceRequestContext("device-a"));
    await flushPromises();

    harness.live.current = buildDeviceRequestContext("device-b");
    void harness.load(requestB.promise, buildDeviceRequestContext("device-b"));
    await flushPromises();

    requestB.resolve(["preview-b"]);
    await flushPromises();
    requestA.resolve(["preview-a"]);
    await flushPromises();

    expect(harness.state.rows).toEqual(["preview-b"]);
  });

  it("preview 的 stale failure 不会清空新设备结果", async () => {
    const harness = createDevicePreviewHarness(buildDeviceRequestContext("device-a"));
    const requestA = createDeferred<string[]>();
    const requestB = createDeferred<string[]>();

    void harness.load(requestA.promise, buildDeviceRequestContext("device-a"));
    await flushPromises();

    harness.live.current = buildDeviceRequestContext("device-b");
    void harness.load(requestB.promise, buildDeviceRequestContext("device-b"));
    await flushPromises();

    requestB.resolve(["preview-b"]);
    await flushPromises();
    requestA.reject(new Error("preview-a failed"));
    await flushPromises();

    expect(harness.state.rows).toEqual(["preview-b"]);
  });

  it("preview same-device success 后 refresh 失败会保留 last-good rows 并标记 error", async () => {
    const harness = createDevicePreviewHarness(buildDeviceRequestContext("device-a"));
    const first = createDeferred<string[]>();
    const second = createDeferred<string[]>();

    void harness.load(first.promise, buildDeviceRequestContext("device-a"));
    first.resolve(["preview-a"]);
    await flushPromises();

    void harness.load(second.promise, buildDeviceRequestContext("device-a"));
    second.reject(new Error("preview unavailable"));
    await flushPromises();

    expect(harness.state.rows).toEqual(["preview-a"]);
    expect(harness.state.error).toBe("preview unavailable");
  });

  it("preview initial failure 显示 unavailable 且不伪装成成功空结果", async () => {
    const harness = createDevicePreviewHarness(buildDeviceRequestContext("device-a"));
    const request = createDeferred<string[]>();

    void harness.load(request.promise, buildDeviceRequestContext("device-a"));
    request.reject(new Error("preview unavailable"));
    await flushPromises();

    expect(harness.state.rows).toEqual([]);
    expect(harness.state.lastSuccessfulContext).toBeNull();
    expect(harness.state.error).toBe("preview unavailable");
  });
});
