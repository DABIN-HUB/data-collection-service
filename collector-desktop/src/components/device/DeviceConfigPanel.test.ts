// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from "vitest";
import { createApp, defineComponent, h, nextTick, type App } from "vue";
import DeviceConfigPanel from "./DeviceConfigPanel.vue";
import { unwrapApiResponse } from "@/api/http";
import { capturedPhase3Receipt } from "@/features/device/utils/phase3-http-receipts.test-fixtures";

const mocks = vi.hoisted(() => ({
  bundle: vi.fn(), validate: vi.fn(), commit: vi.fn(), protocol: vi.fn(), realtime: vi.fn(),
  success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn()
}));
vi.mock("@/api/config.api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/api/config.api")>()),
  getDeviceConfigBundle: mocks.bundle,
  validateDeviceConfigBundle: mocks.validate,
  commitDeviceConfigBundle: mocks.commit,
  getDeviceDiff: vi.fn().mockResolvedValue({})
}));
vi.mock("@/api/protocol.api", () => ({ getProtocol: mocks.protocol }));
vi.mock("@/api/data.api", () => ({ getDeviceRealtimeData: mocks.realtime }));
vi.mock("element-plus", () => ({ ElMessage: { success: mocks.success, error: mocks.error, warning: mocks.warning, info: mocks.info } }));
vi.mock("@/stores/device.store", () => ({
  useDeviceStore: () => ({
    deviceEpochs: {}, runtimeMap: {}, runtimeErrors: {}, deviceErrors: {},
    isDeviceOperating: () => false,
    refreshRuntime: async () => undefined,
    operate: async (action: () => Promise<unknown>) => {
      try { return { ok: true, result: await action() }; }
      catch (error) { return { ok: false, error: error instanceof Error ? error.message : "保存失败" }; }
    }
  })
}));
vi.mock("@/features/point/components/PointEditor.vue", () => ({ default: defineComponent({ render: () => null }) }));
vi.mock("@/features/protocol/components/WorkbenchProtocolForm.vue", () => ({ default: defineComponent({ render: () => null }) }));
vi.mock("@/components/realtime/RealtimeDataPanel.vue", () => ({ default: defineComponent({ render: () => null }) }));
vi.mock("@/components/alarm/AlarmTablePanel.vue", () => ({ default: defineComponent({ render: () => null }) }));
vi.mock("@/components/log/LogPanel.vue", () => ({ default: defineComponent({ render: () => null }) }));

interface PanelState {
  protocolModel: Record<string, unknown>;
  protocolError: string;
  configBundle: { configVersion: number; persistedRevision?: number } | null;
  setActiveTab: (tab: "points" | "alarm") => void;
  saveProtocolConfig: () => Promise<void>;
  loadProtocolConfig: (explicit?: boolean) => Promise<void>;
}
let app: App | null = null;
let host: HTMLElement | null = null;
afterEach(() => {
  app?.unmount();
  host?.remove();
  app = null;
  host = null;
  vi.resetAllMocks();
});
async function settle() { await nextTick(); await new Promise(resolve => setTimeout(resolve, 0)); await nextTick(); }

async function mountPanel() {
  host = document.createElement("div");
  document.body.append(host);
  app = createApp(DeviceConfigPanel, { device: { normalizedId: "dev", displayName: "设备", protocolType: "MODBUS_TCP" } });
  app.config.warnHandler = () => {};
  const stub = defineComponent({ setup: (_, { slots }) => () => h("div", slots.default?.()) });
  for (const name of ["el-button", "el-alert", "el-tag", "el-table", "el-pagination", "el-dialog", "el-empty"]) app.component(name, stub);
  app.component("el-table-column", defineComponent({ render: () => null }));
  app.directive("loading", {});
  app.mount(host);
  await settle();
  return (app._instance as unknown as { setupState: PanelState }).setupState;
}

describe("DeviceConfigPanel 持久修订", () => {
  it("503 UNKNOWN 保留草稿并禁止再次提交，显式重新加载后恢复权威版本", async () => {
    mocks.protocol.mockResolvedValue({ protocol: "MODBUS_TCP", connectionFields: [{ name: "host", type: "string" }] });
    mocks.bundle.mockResolvedValueOnce({ configVersion: 4, persistedRevision: 0, device: { deviceId: "dev" }, connection: { host: "old" }, points: [] });
    mocks.validate.mockResolvedValue({ valid: true }); mocks.realtime.mockResolvedValue([]);
    mocks.commit.mockImplementationOnce(async () => unwrapApiResponse(capturedPhase3Receipt("unconfirmed", "dev"), 503));
    const state = await mountPanel();
    state.protocolModel.host = "draft";
    await state.saveProtocolConfig();
    expect(state.protocolError).toContain("勿重复提交");
    expect(state.configBundle).toMatchObject({ configVersion: 4, persistedRevision: 0 });
    await state.saveProtocolConfig();
    expect(mocks.commit).toHaveBeenCalledTimes(1);
    expect(state.protocolModel.host).toBe("draft");
    mocks.bundle.mockResolvedValueOnce({ configVersion: 5, persistedRevision: 1, device: { deviceId: "dev" }, connection: { host: "authority" }, points: [] });
    await state.loadProtocolConfig(true);
    expect(state.configBundle).toMatchObject({ configVersion: 5, persistedRevision: 1 });
    expect(state.protocolModel.host).toBe("authority");
  });
  for (const [code, reconciled] of [["RECONCILED", true], ["RECOVERY_REQUIRED", false], ["APPLY_FAILED", false]] as const) {
    it(`HTTP200 CONFIG_COMMITTED_${code} 保留草稿/旧版本，只允许显式重读`, async () => {
      mocks.protocol.mockResolvedValue({ protocol: "MODBUS_TCP", connectionFields: [{ name: "host", type: "string" }] });
      mocks.bundle.mockResolvedValueOnce({ configVersion: 4, persistedRevision: 0, device: { deviceId: "dev" }, connection: { host: "old" }, points: [] });
      mocks.validate.mockResolvedValue({ valid: true });
      mocks.realtime.mockResolvedValue([]);
      const kind = code === "RECONCILED" ? "reconciled" : code === "RECOVERY_REQUIRED" ? "recoveryRequired" : "applyFailed";
      mocks.commit.mockResolvedValueOnce(unwrapApiResponse(capturedPhase3Receipt(kind, "dev"), 200));
      const state = await mountPanel();
      state.protocolModel.host = "draft";
      await state.saveProtocolConfig();
      expect(state.protocolError).toContain("显式重新加载");
      if (!reconciled) expect(state.protocolError).toContain("配置已提交，运行态待恢复");
      else expect(state.protocolError).toContain("已完成权威对账");
      expect(state.protocolModel.host).toBe("draft");
      expect(state.configBundle).toMatchObject({ configVersion: 4, persistedRevision: 0 });
      expect(mocks.success).not.toHaveBeenCalled();
      await state.saveProtocolConfig();
      expect(mocks.commit).toHaveBeenCalledTimes(1);
      state.setActiveTab("alarm"); await settle(); state.setActiveTab("points"); await settle();
      expect(mocks.bundle).toHaveBeenCalledTimes(1);
      mocks.bundle.mockResolvedValueOnce({ configVersion: 5, persistedRevision: 1, device: { deviceId: "dev" }, connection: { host: "authoritative" }, points: [] });
      await state.loadProtocolConfig(true);
      expect(state.protocolModel.host).toBe("authoritative");
      expect(state.configBundle).toMatchObject({ configVersion: 5, persistedRevision: 1 });
    });
  }

  it("HTTP200 已提交回执的 deviceIds 不匹配时不得冒充本设备提交", async () => {
    mocks.protocol.mockResolvedValue({ protocol: "MODBUS_TCP", connectionFields: [{ name: "host", type: "string" }] });
    mocks.bundle.mockResolvedValue({ configVersion: 4, persistedRevision: 0, device: { deviceId: "dev" }, connection: { host: "old" }, points: [] });
    mocks.validate.mockResolvedValue({ valid: true }); mocks.realtime.mockResolvedValue([]);
    mocks.commit.mockResolvedValue({ committed: true, reconciled: true, reloadRequired: true, deviceIds: ["other"] });
    const state = await mountPanel();
    state.protocolModel.host = "draft";
    await state.saveProtocolConfig();
    expect(state.protocolError).toContain("身份");
    expect(state.configBundle).toMatchObject({ configVersion: 4, persistedRevision: 0 });
    expect(state.protocolModel.host).toBe("draft");
    expect(mocks.success).not.toHaveBeenCalled();
  });

  it("普通成功必须校验 deviceId，合法响应保留真实版本", async () => {
    mocks.protocol.mockResolvedValue({ protocol: "MODBUS_TCP", connectionFields: [{ name: "host", type: "string" }] });
    mocks.bundle.mockResolvedValue({ configVersion: 4, persistedRevision: 0, device: { deviceId: "dev" }, connection: { host: "old" }, points: [] });
    mocks.validate.mockResolvedValue({ valid: true }); mocks.realtime.mockResolvedValue([]);
    mocks.commit.mockResolvedValueOnce({ deviceId: "other", configVersion: 5, persistedRevision: 1 })
      .mockResolvedValueOnce(unwrapApiResponse(capturedPhase3Receipt("normal", "dev"), 200));
    const state = await mountPanel();
    state.protocolModel.host = "draft";
    await state.saveProtocolConfig();
    expect(state.protocolError).toContain("身份");
    expect(state.configBundle).toMatchObject({ configVersion: 4, persistedRevision: 0 });
    await state.loadProtocolConfig(true);
    state.protocolModel.host = "draft";
    await state.saveProtocolConfig();
    expect(state.configBundle).toMatchObject({ configVersion: 1793253989808, persistedRevision: 1 });
  });
  it("提交读取到的 revision，409 保留草稿，只有显式重新加载才覆盖", async () => {
    mocks.protocol.mockResolvedValue({ protocol: "MODBUS_TCP", connectionFields: [{ name: "host", type: "string" }] });
    mocks.bundle.mockResolvedValue({ configVersion: 4, persistedRevision: 0, device: { deviceId: "dev" }, connection: { host: "old" }, points: [] });
    mocks.validate.mockResolvedValue({ valid: true });
    mocks.commit.mockImplementationOnce(async () => unwrapApiResponse(capturedPhase3Receipt("conflict", "dev"), 409));
    mocks.realtime.mockResolvedValue([]);
    const state = await mountPanel();
    state.protocolModel.host = "draft";
    await state.saveProtocolConfig();
    expect(mocks.validate).toHaveBeenCalledWith("dev", expect.objectContaining({ baseVersion: 4, baseRevision: 0 }));
    expect(mocks.commit).toHaveBeenCalledWith("dev", expect.objectContaining({ baseVersion: 4, baseRevision: 0 }));
    expect(state.protocolError).toContain("显式重新加载");
    expect(state.protocolModel.host).toBe("draft");
    await state.saveProtocolConfig();
    expect(mocks.commit).toHaveBeenCalledTimes(1);
    state.setActiveTab("alarm");
    await settle();
    state.setActiveTab("points");
    await settle();
    expect(mocks.bundle).toHaveBeenCalledTimes(1);
    expect(state.protocolModel.host).toBe("draft");
    await state.loadProtocolConfig(true);
    expect(mocks.bundle).toHaveBeenCalledTimes(2);
    expect(state.protocolModel.host).toBe("old");
  });

  it("提交已持久化但运行态未应用时提示恢复，不提示正常成功", async () => {
    mocks.protocol.mockResolvedValue({ protocol: "MODBUS_TCP", connectionFields: [{ name: "host", type: "string" }] });
    mocks.bundle.mockResolvedValue({ configVersion: 4, persistedRevision: 0, device: { deviceId: "dev" }, connection: { host: "old" }, points: [] });
    mocks.validate.mockResolvedValue({ valid: true });
    mocks.commit.mockResolvedValue({ committed: true, reconciled: false, reloadRequired: true, deviceIds: ["dev"] });
    mocks.realtime.mockResolvedValue([]);
    const state = await mountPanel();
    state.protocolModel.host = "draft";
    await state.saveProtocolConfig();
    expect(state.protocolError).toContain("恢复");
    expect(state.protocolModel.host).toBe("draft");
    expect(mocks.success).not.toHaveBeenCalled();
  });
});
