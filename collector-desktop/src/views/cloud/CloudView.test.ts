// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createApp, defineComponent, h, nextTick, reactive } from "vue";

const mocks = vi.hoisted(() => ({
  list: vi.fn(), detail: vi.fn(), replay: vi.fn(), flush: vi.fn(), test: vi.fn(), metrics: vi.fn(),
  confirm: vi.fn(), success: vi.fn()
}));
vi.mock("@/api/cloud.api", () => ({
  listCloudOutbox: mocks.list, getCloudOutboxDetail: mocks.detail, replayCloudOutbox: mocks.replay,
  flushCloudOutbox: mocks.flush, testCloudLink: mocks.test
}));
vi.mock("@/api/monitor.api", () => ({ getCloudReportMetrics: mocks.metrics }));
vi.mock("element-plus", () => ({
  ElMessageBox: { confirm: mocks.confirm }, ElMessage: { success: mocks.success },
  ElDrawer: defineComponent({ props: ["modelValue"], setup(props, { slots }) {
    return () => props.modelValue ? h("aside", { class: "detail-drawer" }, slots.default?.()) : null;
  } })
}));
vi.mock("@/stores/app.store", () => ({ useAppStore: () => store }));
const store = reactive({ capabilities: { cloud: { monitoringAvailable: true, managementAvailable: true } }, initialize: vi.fn().mockResolvedValue(undefined) });
import CloudView from "./CloudView.vue";

async function settle() { await nextTick(); await new Promise(resolve => setTimeout(resolve, 0)); await nextTick(); }
async function mountPage() {
  const root = document.createElement("div");
  document.body.append(root);
  const app = createApp(CloudView);
  app.mount(root);
  await settle();
  return { root, unmount: () => { app.unmount(); root.remove(); } };
}
async function click(root: HTMLElement, label: string) {
  const button = [...root.querySelectorAll("button")].find(el => el.textContent?.includes(label));
  if (!button) throw new Error(`未找到按钮 ${label}`);
  button.click();
  await settle();
}
beforeEach(() => {
  vi.clearAllMocks();
  mocks.list.mockResolvedValue([{ messageId: "msg-1", localDeviceId: "dev-1", status: "ISOLATED", createdAt: 1000, nextAttemptAt: 2000 }]);
  mocks.metrics.mockResolvedValue({});
  mocks.detail.mockResolvedValue({ summary: { messageId: "msg-1" }, reportData: { value: 42 }, commits: [{ pointId: "p1" }] });
  mocks.confirm.mockResolvedValue("confirm");
  mocks.replay.mockResolvedValue({});
  mocks.flush.mockResolvedValue({ accepted: true });
  mocks.test.mockResolvedValue({ message: "已检查" });
});
describe("CloudView operational interactions", () => {
  it("opens the selected message detail and displays snapshot and commits", async () => {
    const { root, unmount } = await mountPage();
    expect(root.textContent).toContain("Outbox / ACK 指标");
    await click(root, "查看详情");
    expect(mocks.detail).toHaveBeenCalledWith("msg-1");
    expect(root.textContent).toContain('"value": 42');
    expect(root.textContent).toContain('"pointId": "p1"');
    unmount();
  });
  it("shows detail errors rather than a successful empty state", async () => {
    mocks.detail.mockRejectedValue(new Error("详情读取失败"));
    const { root, unmount } = await mountPage();
    await click(root, "查看详情");
    expect(root.textContent).toContain("详情读取失败");
    unmount();
  });
  it("renders WAITING_CONFIG without exposing replay to non-isolated messages", async () => {
    mocks.list.mockResolvedValueOnce([{ messageId: "waiting-1", status: "WAITING_CONFIG" }]);
    const { root, unmount } = await mountPage();
    expect(root.textContent).toContain("WAITING_CONFIG");
    expect([...root.querySelectorAll("button")].some(el => el.textContent?.includes("Replay"))).toBe(false);
    unmount();
  });
  it("replays after confirmation and reloads metrics and list", async () => {
    const { root, unmount } = await mountPage();
    await click(root, "Replay");
    expect(mocks.confirm).toHaveBeenCalledOnce();
    expect(mocks.replay).toHaveBeenCalledWith("msg-1");
    expect(mocks.metrics).toHaveBeenCalledTimes(2);
    expect(mocks.list).toHaveBeenCalledTimes(2);
    unmount();
  });
  it("keeps replay cancellation silent but surfaces API failure", async () => {
    const { root, unmount } = await mountPage();
    mocks.confirm.mockRejectedValueOnce("cancel");
    await click(root, "Replay");
    expect(mocks.replay).not.toHaveBeenCalled();
    mocks.replay.mockRejectedValueOnce(new Error("服务端拒绝重放"));
    await click(root, "Replay");
    expect(root.textContent).toContain("服务端拒绝重放");
    unmount();
  });
  it("shows loading and empty detail distinctly", async () => {
    let finish!: (value: null) => void;
    mocks.detail.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
    const { root, unmount } = await mountPage();
    await click(root, "查看详情");
    expect(root.textContent).toContain("详情加载中");
    finish(null);
    await settle();
    expect(root.textContent).toContain("消息详情为空");
    unmount();
  });
  it("does not overwrite a newer detail when an older response finishes late", async () => {
    let finish!: (value: unknown) => void;
    mocks.detail.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
    mocks.detail.mockResolvedValueOnce({ summary: { messageId: "msg-2" }, reportData: { value: 99 }, commits: [] });
    mocks.list.mockResolvedValueOnce([
      { messageId: "msg-1", status: "ISOLATED" }, { messageId: "msg-2", status: "WAITING_CONFIG" }
    ]);
    const { root, unmount } = await mountPage();
    const buttons = [...root.querySelectorAll("button")].filter(el => el.textContent?.includes("查看详情"));
    buttons[0]?.click();
    buttons[1]?.click();
    await settle();
    finish({ summary: { messageId: "msg-1" }, reportData: { value: 1 }, commits: [] });
    await settle();
    expect(root.textContent).toContain('"value": 99');
    expect(root.textContent).not.toContain('"value": 1');
    unmount();
  });
  it("does not dispatch flush after cancellation", async () => {
    const { root, unmount } = await mountPage();
    mocks.confirm.mockRejectedValueOnce("cancel");
    await click(root, "Flush 到期消息");
    expect(mocks.flush).not.toHaveBeenCalled();
    unmount();
  });
  it("surfaces flush and test API errors", async () => {
    const { root, unmount } = await mountPage();
    mocks.flush.mockRejectedValueOnce(new Error("调度失败"));
    await click(root, "Flush 到期消息");
    expect(root.textContent).toContain("调度失败");
    mocks.test.mockRejectedValueOnce(new Error("测试失败"));
    await click(root, "测试云链路");
    expect(root.textContent).toContain("测试失败");
    unmount();
  });
});
