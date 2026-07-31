const state = {
  token: localStorage.getItem("collectorToken") || "",
  devices: [],
  protocols: [],
  runtimeStatus: {},
  currentProtocol: null,
  currentLocalProtocol: null,
  localDeviceEditingId: null,
  deviceSearch: "",
  selectedDeviceId: "",
  realtimeTimer: null,
  realtimeAutoRefreshEnabled: false,
  realtimeLoading: false,
  realtimeQueued: false,
  realtimeSearchTimer: null,
  realtimeRequestSeq: 0,
  lastSuggestedCommandText: "",
  realtimeSearch: "",
  realtimeRawPoints: [],
  realtimePoints: [],
  selectedRealtimePointKey: null,
  activeWorkbenchTab: "points"
};

const $ = (selector) => document.querySelector(selector);
const API_BASE = resolveContextPath();
const HIDDEN_PROTOCOLS = new Set(["OPC_UA_PLC4X"]);
const THREAD_POOL_LABELS = Object.freeze({
  reportExecutor: "云端发送线程池",
  batchDispatcherExecutor: "批次分发线程池",
  asyncCollectorExecutor: "异步采集线程池",
  dataProcessorExecutor: "数据处理线程池",
  cacheAsyncExecutor: "缓存异步线程池",
  telemetryCacheStageExecutor: "实时缓存阶段",
  telemetryStreamStageExecutor: "流缓存阶段",
  telemetryHistoryStageExecutor: "历史存储阶段",
  telemetryReportStageExecutor: "上报处理阶段",
  timeSliceScheduler: "时间片调度池"
});

const adaptiveDefaults = {
  baseCollectionInterval: 2000,
  minCollectionInterval: 100,
  maxCollectionInterval: 3600000,
  pointChangeThreshold: 1
};

const controlCommandPresets = {
  DEFAULT: {
    helpText: "默认示例。请把 command 和 params 替换为当前采集器支持的具体操作。",
    payload: { command: "status", params: {} }
  },
  SIEMENS_S7: {
    helpText: "S7 支持 DB1.DBW0 这类简写地址，也支持 %DB1.DBX0.0:BOOL 这类 PLC4X 原生地址。MODE/SYS/USR/ALM 是订阅模式，不是普通点位地址。",
    payload: { command: "diagnostic", params: {} }
  }
};

document.addEventListener("DOMContentLoaded", () => {
  $("#tokenInput").value = state.token;
  bindEvents();
  bindConsoleShell();
  startLiveClock();
  refreshAll();
});

function bindEvents() {
  $("#saveTokenBtn").addEventListener("click", () => {
    state.token = $("#tokenInput").value.trim();
    localStorage.setItem("collectorToken", state.token);
    toast("令牌已保存");
  });
  $("#refreshAllBtn").addEventListener("click", refreshAll);
  $("#reloadDevicesBtn").addEventListener("click", () => {
    window.location.hash = "device";
  });
  $("#openLocalDeviceBtn").addEventListener("click", () => openConfiguredLocalDeviceForm());
  $("#cancelLocalDeviceBtn").addEventListener("click", closeLocalDeviceForm);
  $("#saveLocalDeviceBtn").addEventListener("click", saveLocalDevice);
  $("#formatLocalPointsBtn").addEventListener("click", formatLocalPointsJson);
  $("#exportConfigBtn").addEventListener("click", exportConfig);
  $("#syncConfigBtn").addEventListener("click", syncConfig);
  $("#protocolSelect").addEventListener("change", renderSelectedProtocol);
  $("#localProtocolSelect").addEventListener("change", renderLocalProtocolSelection);
  $("#loadConnectionBtn").addEventListener("click", loadConnection);
  $("#saveConnectionBtn").addEventListener("click", saveConnection);
  $("#toggleRealtimeBtn").addEventListener("click", toggleRealtime);
  $("#resetAdaptiveBtn").addEventListener("click", resetAdaptive);
  $("#writePointsBtn").addEventListener("click", writePoints);
  $("#executeCommandBtn").addEventListener("click", executeCommand);
  $("#loadShadowBtn").addEventListener("click", loadShadow);
  $("#saveDesiredBtn").addEventListener("click", saveDesired);
  $("#clearDesiredBtn").addEventListener("click", clearDesired);
}

function bindConsoleShell() {
  document.querySelectorAll("[data-console-tab]").forEach((button) => {
    button.addEventListener("click", () => activateConsoleTab(button.dataset.consoleTab));
  });
  document.querySelectorAll("[data-workbench-tab]").forEach((button) => {
    button.addEventListener("click", () => activateWorkbenchTab(button.dataset.workbenchTab));
  });
  updateRealtimeToggleButton();
  document.addEventListener("visibilitychange", handleVisibilityChange);

  ["#realtimeDeviceSelect", "#connectionDeviceSelect", "#controlDeviceSelect", "#shadowDeviceSelect"].forEach((selector) => {
    const select = $(selector);
    if (!select) {
      return;
    }
    select.addEventListener("change", (event) => {
      const deviceId = String(event.target.value || "");
      const activatePoints = selector === "#realtimeDeviceSelect";
      syncDeviceContext(deviceId, { activatePoints, loadRealtime: true }).catch((error) => toast(error.message, true));
    });
  });

  const deviceSearchInput = $("#deviceSearchInput");
  if (deviceSearchInput) {
    deviceSearchInput.addEventListener("input", (event) => {
      state.deviceSearch = String(event.target.value || "").trim().toLowerCase();
      renderDevices();
    });
  }

  const pointSearch = $("#devicePointSearch");
  if (pointSearch) {
    pointSearch.addEventListener("input", (event) => {
      state.realtimeSearch = String(event.target.value || "").trim().toLowerCase();
      if (state.realtimeSearchTimer) {
        clearTimeout(state.realtimeSearchTimer);
      }
      state.realtimeSearchTimer = window.setTimeout(() => {
        state.realtimeSearchTimer = null;
        renderRealtimeTable();
      }, 120);
    });
  }

  const realtimeRows = $("#realtimeRows");
  if (realtimeRows) {
    realtimeRows.addEventListener("click", (event) => {
      const row = event.target.closest("tr[data-point-key]");
      if (!row) {
        return;
      }
      selectRealtimePoint(row.dataset.pointKey);
    });
  }
}

function activateConsoleTab(tabName) {
  document.querySelectorAll("[data-console-tab]").forEach((button) => {
    button.classList.toggle("is-active", button.dataset.consoleTab === tabName);
  });
  document.querySelectorAll("[data-console-panel]").forEach((panel) => {
    const active = panel.dataset.consolePanel === tabName;
    panel.classList.toggle("hidden", !active);
    panel.classList.toggle("console-module-active", active);
  });
}

function activateWorkbenchTab(tabName) {
  state.activeWorkbenchTab = tabName || "points";
  document.querySelectorAll("[data-workbench-tab]").forEach((button) => {
    button.classList.toggle("is-active", button.dataset.workbenchTab === state.activeWorkbenchTab);
  });
  document.querySelectorAll("[data-workbench-panel]").forEach((panel) => {
    const active = panel.dataset.workbenchPanel === state.activeWorkbenchTab;
    panel.classList.toggle("hidden", !active);
    panel.classList.toggle("console-module-active", active);
  });
}

function currentDeviceSelection() {
  return state.selectedDeviceId
    || $("#realtimeDeviceSelect")?.value
    || $("#connectionDeviceSelect")?.value
    || $("#controlDeviceSelect")?.value
    || $("#shadowDeviceSelect")?.value
    || "";
}

function resolveAvailableDeviceId(deviceId = "") {
  const deviceIds = state.devices
    .map((device) => device.id || device.deviceId)
    .filter(Boolean);
  if (!deviceIds.length) {
    return "";
  }
  if (deviceId && deviceIds.includes(deviceId)) {
    return deviceId;
  }
  const current = currentDeviceSelection();
  if (current && deviceIds.includes(current)) {
    return current;
  }
  return deviceIds[0];
}

function syncDeviceSelectValues(deviceId) {
  ["#connectionDeviceSelect", "#realtimeDeviceSelect", "#controlDeviceSelect", "#shadowDeviceSelect"].forEach((selector) => {
    const select = $(selector);
    if (!select) {
      return;
    }
    const exists = Array.from(select.options).some((item) => item.value === deviceId);
    if (exists) {
      select.value = deviceId;
    } else if (!deviceId && select.options.length) {
      select.selectedIndex = 0;
    } else {
      select.value = "";
    }
  });
}

function syncDeviceContext(deviceId, { activatePoints = false, loadRealtime: shouldLoadRealtime = false } = {}) {
  const resolvedDeviceId = resolveAvailableDeviceId(deviceId);
  state.selectedDeviceId = resolvedDeviceId;
  syncDeviceSelectValues(resolvedDeviceId);
  syncProtocolSelectionToDevice(false);
  syncControlCommandExample();
  syncSelectedDeviceSummary(resolvedDeviceId);
  renderDevices();
  syncRealtimeTimer();

  if (!resolvedDeviceId) {
    state.realtimeRawPoints = [];
    state.realtimePoints = [];
    clearSelectedPointInspector();
    renderRealtimeTable();
    return Promise.resolve("");
  }

  if (activatePoints) {
    activateWorkbenchTab("points");
  }
  if (shouldLoadRealtime) {
    return loadRealtime().then(() => resolvedDeviceId);
  }
  return Promise.resolve(resolvedDeviceId);
}

function selectedDeviceId() {
  return currentDeviceSelection();
}

function selectDevice(deviceId) {
  if (!deviceId) {
    return;
  }
  syncDeviceContext(deviceId, { activatePoints: true, loadRealtime: true }).catch((error) => toast(error.message, true));
}

function realtimePointKey(point, index = 0) {
  return String(point?.pointId || point?.pointCode || point?.pointName || point?.address || `point-${index}`);
}

function setInspectorField(selector, value) {
  const target = $(selector);
  if (!target) {
    return;
  }
  target.value = value;
}

function clearSelectedPointInspector() {
  state.selectedRealtimePointKey = null;
  state.realtimePoints = [];
  $("#selectedPointEmpty")?.classList.remove("hidden");
  $("#selectedPointPanel")?.classList.add("hidden");
  const tag = $("#inspectorPointTag");
  if (tag) {
    tag.textContent = "未选择点位";
  }
  document.querySelectorAll("#realtimeRows tr[data-point-key]").forEach((row) => row.classList.remove("is-selected"));
}

function renderSelectedPointInspector() {
  const point = state.realtimePoints.find((item) => item.__pointKey === state.selectedRealtimePointKey) || null;
  if (!point) {
    $("#selectedPointEmpty")?.classList.remove("hidden");
    $("#selectedPointPanel")?.classList.add("hidden");
    const tag = $("#inspectorPointTag");
    if (tag) {
      tag.textContent = "未选择点位";
    }
    return;
  }

  const qualityText = realtimePointQualityText(point);
  const address = point.address || point.registerAddress || point.pointAddress || "-";
  const scale = point.scalingFactor ?? point.scale ?? point.factor ?? "-";
  const pointCode = point.pointCode || point.pointId || "-";
  const unit = point.unit || point.sourceUnit || "-";
  const processText = realtimePointProcessingTimeText(point);

  $("#selectedPointEmpty")?.classList.add("hidden");
  $("#selectedPointPanel")?.classList.remove("hidden");
  $("#inspectorPointTag").textContent = point.pointName || pointCode;
  $("#inspectorPointName").textContent = point.pointName || point.pointId || "-";
  $("#inspectorPointCodeText").textContent = pointCode;
  $("#inspectorPointUnitText").textContent = unit;
  $("#inspectorPointProcessText").textContent = processText;

  const badge = $("#inspectorPointQualityBadge");
  if (badge) {
    badge.textContent = qualityText;
    badge.className = `badge ${realtimePointQualityBadgeClass(point)}`;
  }

  setInspectorField("#inspectorPointCode", pointCode);
  setInspectorField("#inspectorPointType", point.dataType || point.driverDataType || point.type || "-");
  setInspectorField("#inspectorPointAddress", formatValue(address));
  setInspectorField("#inspectorPointAccess", point.readWrite || point.accessMode || "-");
  setInspectorField("#inspectorPointScale", formatValue(scale));
  setInspectorField("#inspectorPointValue", realtimePointValueText(point));
  setInspectorField("#inspectorPointRawValue", realtimePointRawValueText(point));
  setInspectorField("#inspectorPointQuality", qualityText);
  setInspectorField("#inspectorPointUnit", unit);
  setInspectorField("#inspectorPointProcessingTime", processText);
}

function selectRealtimePoint(pointKey) {
  state.selectedRealtimePointKey = String(pointKey || "");
  document.querySelectorAll("#realtimeRows tr[data-point-key]").forEach((row) => {
    row.classList.toggle("is-selected", row.dataset.pointKey === state.selectedRealtimePointKey);
  });
  renderSelectedPointInspector();
}

function startLiveClock() {
  renderLiveClock();
  window.setInterval(renderLiveClock, 1000);
}

function renderLiveClock() {
  const target = $("#liveClock");
  if (!target) {
    return;
  }
  target.textContent = new Date().toLocaleTimeString("zh-CN", { hour12: false });
}

function localizePointQuality(quality) {
  const normalized = String(quality || "").trim().toUpperCase();
  if (normalized === "GOOD") {
    return "正常";
  }
  if (normalized === "BAD") {
    return "异常";
  }
  return quality || "-";
}

function hasRealtimeDisplayValue(value) {
  return value !== null && value !== undefined && (typeof value !== "string" || value.trim() !== "");
}

function hasRealtimeCachedValue(point) {
  if (!point) {
    return false;
  }
  if (point.hasCachedValue === true) {
    return true;
  }
  if (point.hasCachedValue === false) {
    return false;
  }
  return hasRealtimeDisplayValue(point.value);
}

function realtimePointValueText(point) {
  return hasRealtimeCachedValue(point) ? formatValue(point?.value) : "无缓存";
}

function realtimePointRawValueText(point) {
  return hasRealtimeCachedValue(point) ? formatValue(point?.rawValue) : "无缓存";
}

function realtimePointQualityText(point) {
  if (!point || point.qualityAvailable === false) {
    return "未处理";
  }
  if (hasRealtimeDisplayValue(point.quality)) {
    return localizePointQuality(point.quality);
  }
  if (point.qualityAcceptable === false) {
    return "异常";
  }
  if (point.qualityAcceptable === true) {
    return "正常";
  }
  return "未处理";
}

function realtimePointQualityStatusClass(point) {
  if (!point || point.qualityAvailable === false) {
    return "status-warn";
  }
  return point.qualityAcceptable === false ? "status-bad" : "status-good";
}

function realtimePointQualityBadgeClass(point) {
  if (!point || point.qualityAvailable === false) {
    return "badge-remote";
  }
  return point.qualityAcceptable === false ? "badge-alert" : "badge-remote";
}

function realtimePointRuntimeTone(point) {
  if (!point || point.qualityAvailable === false) {
    return "default";
  }
  return point.qualityAcceptable === false ? "bad" : "good";
}

function realtimePointProcessingTimeText(point) {
  if (!point || point.processingTimeAvailable === false || !hasRealtimeDisplayValue(point.processingTime)) {
    return "-";
  }
  return `${point.processingTime} ms`;
}

function localizeDeviceStatus(status) {
  switch (String(status || "").toUpperCase()) {
    case "ONLINE":
      return "在线";
    case "RUNNING":
      return "启动中";
    case "OFFLINE":
      return "离线";
    case "CONNECTING":
      return "连接中";
    case "RECONNECTING":
      return "重连中";
    case "DEGRADED":
      return "降级运行";
    case "FAILED":
      return "采集失败";
    case "STOPPED":
      return "已停止";
    case "ERROR":
      return "异常";
    case "UNKNOWN":
    case "":
      return "未知";
    default:
      return status || "未知";
  }
}

function syncSelectedDeviceSummary(deviceId = selectedDeviceId()) {
  const device = deviceId ? getDeviceById(deviceId) : null;
  const runtime = deviceId ? getRuntimeStatus(deviceId) : null;
  const status = resolveDeviceStatus(device, runtime);
  const address = [device?.ipAddress, device?.port].filter(Boolean).join(":") || device?.host || "-";
  const dot = $("#selectedDeviceStatusDot");

  $("#selectedDeviceName").textContent = device?.deviceName || deviceId || "暂无设备";
  $("#selectedDeviceProtocol").textContent = device?.protocolType || device?.connectionType || "-";
  $("#selectedDeviceAddress").textContent = address;
  $("#selectedDeviceInterval").textContent = device?.collectionInterval !== undefined && device?.collectionInterval !== null
    ? `${device.collectionInterval} ms`
    : "-";
  $("#selectedDeviceStatus").textContent = localizeDeviceStatus(status);

  if (dot) {
    const active = status === "ONLINE" || status === "RUNNING";
    dot.classList.toggle("online", active);
    dot.classList.toggle("offline", !active);
  }
}

function matchesDeviceSearch(device) {
  const search = String(state.deviceSearch || "").trim().toLowerCase();
  if (!search) {
    return true;
  }
  return [
    device?.deviceName,
    device?.id,
    device?.deviceId,
    device?.protocolType,
    device?.connectionType,
    device?.ipAddress,
    device?.host,
    device?.port
  ].some((value) => String(value || "").toLowerCase().includes(search));
}

function updateDeviceSearchMeta(filteredCount, totalCount) {
  const target = $("#deviceSearchMeta");
  if (!target) {
    return;
  }
  if (!totalCount) {
    target.textContent = "暂无设备";
    return;
  }
  target.textContent = state.deviceSearch
    ? `命中 ${filteredCount}/${totalCount}`
    : `共 ${totalCount} 台设备`;
}

function setPanelState(selector, message, tone = "info") {
  const target = $(selector);
  if (!target) {
    return;
  }
  if (!message) {
    target.textContent = "";
    target.className = "panel-state hidden";
    return;
  }
  target.textContent = message;
  target.className = `panel-state panel-state-${tone}`;
}

function setDeviceListState(message, tone = "info") {
  setPanelState("#deviceListState", message, tone);
}

function setRealtimeState(message, tone = "info") {
  setPanelState("#realtimeState", message, tone);
}

function setProtocolFormState(message, tone = "info") {
  setPanelState("#protocolFormState", message, tone);
}

function setProtocolDiffState(message, tone = "info") {
  setPanelState("#protocolDiffState", message, tone);
}

function setShadowState(message, tone = "info") {
  setPanelState("#shadowState", message, tone);
}

function setMonitorState(message, tone = "info") {
  setPanelState("#monitorState", message, tone);
}

function matchesRealtimeSearch(point) {
  const search = String(state.realtimeSearch || "").trim().toLowerCase();
  if (!search) {
    return true;
  }
  return [
    point?.pointName,
    point?.pointCode,
    point?.address,
    point?.registerAddress,
    point?.pointId
  ].some((value) => String(value || "").toLowerCase().includes(search));
}

function updateRealtimeToggleButton() {
  const target = $("#toggleRealtimeBtn");
  if (!target) {
    return;
  }
  target.textContent = state.realtimeAutoRefreshEnabled ? "停止自动刷新" : "自动刷新";
  target.classList.toggle("is-active", state.realtimeAutoRefreshEnabled);
}

function syncRealtimeTimer() {
  if (state.realtimeTimer) {
    clearInterval(state.realtimeTimer);
    state.realtimeTimer = null;
  }
  if (!state.realtimeAutoRefreshEnabled || document.hidden || !selectedDeviceId()) {
    updateRealtimeToggleButton();
    return;
  }
  state.realtimeTimer = window.setInterval(() => {
    requestRealtimeRefresh();
  }, 3000);
  updateRealtimeToggleButton();
}

function handleVisibilityChange() {
  syncRealtimeTimer();
  if (!document.hidden && state.realtimeAutoRefreshEnabled) {
    requestRealtimeRefresh();
  }
}

function requestRealtimeRefresh() {
  if (!selectedDeviceId()) {
    return;
  }
  if (state.realtimeLoading) {
    state.realtimeQueued = true;
    return;
  }
  loadRealtime().catch((error) => toast(error.message, true));
}

function renderRealtimeTable() {
  const deviceId = selectedDeviceId();
  const rowsTarget = $("#realtimeRows");
  const stageNote = $("#realtimeStageNote");
  if (!rowsTarget) {
    return;
  }
  if (!deviceId) {
    rowsTarget.innerHTML = `<tr><td colspan="9">请选择设备后查看实时数据</td></tr>`;
    setRealtimeState("请选择设备后查看实时数据", "muted");
    if (stageNote) {
      stageNote.textContent = "选择设备后查看当前点位快照";
    }
    clearSelectedPointInspector();
    syncSelectedDeviceSummary("");
    return;
  }

  syncSelectedDeviceSummary(deviceId);
  const points = state.realtimeRawPoints
    .filter((point) => matchesRealtimeSearch(point))
    .map((point, index) => ({
      ...point,
      __pointKey: realtimePointKey(point, index)
    }));

  state.realtimePoints = points;
  if (!points.length) {
    state.selectedRealtimePointKey = null;
  } else if (!points.some((point) => point.__pointKey === state.selectedRealtimePointKey)) {
    state.selectedRealtimePointKey = points[0].__pointKey;
  }

  if (stageNote) {
    stageNote.textContent = state.realtimeRawPoints.length
      ? `当前设备 ${points.length}/${state.realtimeRawPoints.length} 个点位可见`
      : "当前设备暂无实时点位";
  }

  const rows = points.map((point) => {
    const qualityText = realtimePointQualityText(point);
    const qualityClass = realtimePointQualityStatusClass(point);
    const address = point.address || point.registerAddress || point.pointAddress || "-";
    const scale = point.scalingFactor ?? point.scale ?? point.factor ?? "-";
    const processText = realtimePointProcessingTimeText(point);
    return `
      <tr data-point-key="${escapeAttr(point.__pointKey)}" class="${point.__pointKey === state.selectedRealtimePointKey ? "is-selected" : ""}">
        <td>${escapeHtml(point.pointName || point.pointId || "-")}</td>
        <td><code>${escapeHtml(point.pointCode || point.pointId || "-")}</code></td>
        <td>${escapeHtml(point.dataType || point.driverDataType || point.type || "-")}</td>
        <td>${escapeHtml(formatValue(address))}</td>
        <td>${escapeHtml(point.readWrite || point.accessMode || "-")}</td>
        <td>${escapeHtml(formatValue(scale))}</td>
        <td><strong>${escapeHtml(realtimePointValueText(point))}</strong></td>
        <td class="${qualityClass}">${escapeHtml(qualityText)}</td>
        <td>${escapeHtml(processText)}</td>
      </tr>`;
  }).join("");

  if (!state.realtimeRawPoints.length) {
    rowsTarget.innerHTML = `<tr><td colspan="9">当前设备暂无实时数据</td></tr>`;
    setRealtimeState("当前设备暂无实时数据", "muted");
    clearSelectedPointInspector();
    return;
  }

  rowsTarget.innerHTML = rows || `<tr><td colspan="9">没有匹配的点位</td></tr>`;
  if (!points.length) {
    setRealtimeState("没有匹配当前筛选条件的点位", "warning");
    clearSelectedPointInspector();
    return;
  }

  setRealtimeState("");
  renderSelectedPointInspector();
}

async function refreshAll() {
  const results = await Promise.allSettled([
    loadProtocols(),
    loadDevices(),
    loadOverview(),
    loadMonitor()
  ]);
  if (results.some((item) => item.status === "fulfilled")) {
    $("#lastRefresh").textContent = new Date().toLocaleString();
  }
  const failures = results.filter((item) => item.status === "rejected");
  if (failures.length === results.length) {
    toast(failures[0].reason?.message || "刷新失败", true);
  } else if (failures.length) {
    toast(`部分模块刷新失败（${failures.length}/${results.length}）`, true);
  }
}

async function callApi(path, options = {}) {
  const headers = new Headers(options.headers || {});
  if (!headers.has("Content-Type") && options.body) {
    headers.set("Content-Type", "application/json");
  }
  if (state.token) {
    headers.set("X-Collector-Token", state.token);
  }
  const response = await fetch(`${API_BASE}${path}`, { ...options, headers });
  const text = await response.text();
  let body = {};
  if (text) {
    try {
      body = JSON.parse(text);
    } catch (error) {
      body = { message: text.trim() || `HTTP ${response.status}` };
    }
  }
  if (response.status === 401) {
    state.token = "";
    localStorage.removeItem("collectorToken");
    const tokenInput = $("#tokenInput");
    if (tokenInput) {
      tokenInput.value = "";
    }
  }
  if (!response.ok) {
    throw apiError(body.message || `HTTP ${response.status}`, body, response.status);
  }
  if (body.status === "error") {
    throw apiError(body.message || "请求失败", body, response.status);
  }
  if (typeof body.code === "number" && body.code !== 200) {
    throw apiError(body.message || `业务错误码 ${body.code}`, body, response.status);
  }
  return body;
}

function apiError(message, body, httpStatus) {
  const error = new Error(message);
  error.body = body;
  error.httpStatus = httpStatus;
  return error;
}

function resolveContextPath() {
  const marker = "/admin/";
  const pathname = window.location.pathname;
  const index = pathname.indexOf(marker);
  if (index <= 0) {
    return "";
  }
  return pathname.substring(0, index);
}

function dataOf(body) {
  return body && Object.prototype.hasOwnProperty.call(body, "data") ? body.data : body;
}

async function loadOverview() {
  const alarmStartTs = Date.now() - 24 * 60 * 60 * 1000;
  const [summaryBody, runningBody, healthBody, cacheBody, deviceBody, errorsBody, systemBody, perfDetailBody, reportBody, storageBody, alarmBody] = await Promise.allSettled([
    callApi("/api/config/summary"),
    callApi("/api/device/running"),
    callApi("/health"),
    callApi("/monitor/cache"),
    callApi("/monitor/devices"),
    callApi("/monitor/errors"),
    callApi("/monitor/system"),
    callApi("/monitor/perf/detail"),
    callApi("/monitor/report"),
    callApi("/monitor/storage"),
    callApi(`/api/data/history/alarms?limit=8&startTs=${alarmStartTs}`)
  ]);

  const availability = {
    summary: isFulfilled(summaryBody),
    running: isFulfilled(runningBody),
    health: isFulfilled(healthBody),
    cache: isFulfilled(cacheBody),
    devices: isFulfilled(deviceBody),
    errors: isFulfilled(errorsBody),
    system: isFulfilled(systemBody),
    performance: isFulfilled(perfDetailBody),
    report: isFulfilled(reportBody),
    storage: isFulfilled(storageBody),
    alarm: isFulfilled(alarmBody)
  };
  const summary = settledData(summaryBody, {});
  const running = settledData(runningBody, []);
  const healthData = settledData(healthBody, {});
  const cache = settledData(cacheBody, {});
  const deviceData = settledData(deviceBody, {});
  const errorData = settledData(errorsBody, {});
  const systemData = settledData(systemBody, {});
  const perfDetail = settledData(perfDetailBody, {});
  const reportData = settledData(reportBody, {});
  const storageData = settledData(storageBody, {});
  const alarmData = normalizeAlarmData(settledBody(alarmBody, { status: "unavailable", data: [] }));

  const stats = summary.cacheStats || {};
  const totalDevices = availability.summary ? numberValue(stats.deviceCount ?? summary.deviceCount, 0) : null;
  const runningDevices = availability.running && Array.isArray(running) ? running.length : null;
  const connectedDevices = availability.devices ? numberValue(deviceData.activeConnections, 0) : null;
  const disconnectedDevices = totalDevices === null || connectedDevices === null
    ? null : Math.max(totalDevices - connectedDevices, 0);
  const pointCount = availability.summary ? numberValue(stats.pointCount ?? summary.pointCount, 0) : null;
  const connectionCount = availability.summary ? numberValue(stats.connectionCount ?? summary.connectionCount, 0) : null;
  const healthStatus = availability.health ? healthData.status || healthData.overallStatus || "UNKNOWN" : "UNKNOWN";
  const alarmCount = availability.alarm && alarmData.status === "success"
    ? numberValue(alarmData.total ?? alarmData.count, safeArray(alarmData.data).length)
    : null;
  const reportStatus = availability.report ? reportData.status || "UNKNOWN" : "UNKNOWN";
  const cacheAccess = availability.cache ? numberValue(cache.totalAccess ?? cache.totalReads, 0) : null;
  const cacheHitRatio = cacheAccess > 0 ? percentageRatio(cache.totalHitRate) : null;

  updateNodeIdentity(summary, availability.summary);
  updateRuntimeIndicator(healthStatus, runningDevices, availability.health);
  const onlineCount = $("#headingOnlineCount");
  if (onlineCount) {
    onlineCount.textContent = `${metricValue(connectedDevices)}/${metricValue(totalDevices)}`;
    onlineCount.closest(".heading-online")?.classList.toggle("is-unknown", connectedDevices === null || totalDevices === null);
  }

  renderCards("#overviewCards", [
    {
      label: "采集器总数",
      value: metricValue(totalDevices),
      icon: "factory.svg",
      meta: [["已连接", metricValue(connectedDevices)], ["未连接", metricValue(disconnectedDevices)]],
      tone: "blue"
    },
    {
      label: "点位总数",
      value: metricValue(pointCount),
      icon: "chart-timeline-variant.svg",
      meta: [["连接配置", metricValue(connectionCount)], ["上报属性", availability.report ? numberValue(reportData.configured?.reportFieldPointCount, 0) : "-"]],
      tone: "green"
    },
    {
      label: "全局告警",
      value: alarmData.status === "disabled" ? "未启用" : metricValue(alarmCount),
      icon: "alert-circle.svg",
      subtext: alarmData.status === "disabled"
        ? "TDengine 告警历史未启用"
        : (alarmCount === null ? "告警数据不可用" : "最近 24 小时告警总数"),
      tone: alarmCount === null ? "muted" : (alarmCount > 0 ? "orange" : "teal")
    },
    {
      label: "运行设备",
      value: metricValue(runningDevices),
      icon: "router-wireless.svg",
      meta: [["缺失连接", availability.devices ? safeArray(deviceData.missingConnections).length : "-"], ["健康连接", availability.devices ? numberValue(deviceData.healthyDevices, 0) : "-"]],
      tone: "orange"
    },
    {
      label: "缓存命中率",
      value: cacheHitRatio === null ? "-" : percent(cacheHitRatio),
      ringValue: cacheHitRatio,
      subtext: cacheAccess === null ? "缓存指标不可用" : (cacheAccess === 0 ? "暂无缓存访问" : `总访问 ${cacheAccess}`),
      tone: "purple"
    },
    {
      label: "云上报链路",
      value: cloudStatusLabel(reportStatus),
      icon: "cloud-upload.svg",
      subtext: availability.report ? reportData.statusText || "上报状态未知" : "上报监控数据不可用",
      tone: homeTone(reportStatus)
    }
  ]);

  renderHomeDashboard({
    summary,
    running,
    healthData,
    cache,
    deviceData,
    errorData,
    systemData,
    perfDetail,
    reportData,
    storageData,
    alarmData,
    totalDevices,
    onlineDevices: connectedDevices,
    runningDevices,
    pointCount,
    connectionCount,
    healthStatus,
    availability
  });
}

function updateNodeIdentity(summary, available) {
  const target = $("#nodeIdentity");
  if (target) {
    target.textContent = available && summary.serviceId ? String(summary.serviceId) : "未配置";
  }
}

function updateRuntimeIndicator(healthStatus, runningDeviceCount, available) {
  const indicator = $("#runtimeIndicator");
  const sidebarStatus = $("#systemStatus");
  const normalizedStatus = String(healthStatus || "UNKNOWN").toUpperCase();
  const tone = !available || normalizedStatus === "UNKNOWN"
    ? "unknown" : (normalizedStatus === "UP" ? "ok" : (normalizedStatus === "DEGRADED" ? "warning" : "error"));
  const detail = !available
    ? "服务状态不可用"
    : (normalizedStatus === "UP"
      ? (runningDeviceCount > 0 ? `运行中（${runningDeviceCount} 台设备）` : "服务正常（暂无运行设备）")
      : (normalizedStatus === "DEGRADED" ? "服务降级" : (normalizedStatus === "UNKNOWN" ? "状态未知" : `服务异常（${normalizedStatus}）`)));
  if (indicator) {
    indicator.lastChild.textContent = detail;
    setStatusClass(indicator, tone);
  }
  if (sidebarStatus) {
    sidebarStatus.querySelector("span").textContent = detail;
    setStatusClass(sidebarStatus, tone);
  }
}

function setStatusClass(target, tone) {
  target.classList.remove("is-ok", "is-warning", "is-error", "is-unknown");
  target.classList.add(`is-${tone}`);
}

function settledData(result, fallback = {}) {
  if (!result || result.status !== "fulfilled") {
    return fallback;
  }
  const value = dataOf(result.value);
  return value === null || value === undefined ? fallback : value;
}

function isFulfilled(result) {
  return Boolean(result && result.status === "fulfilled");
}

function metricValue(value) {
  return value === null || value === undefined ? "-" : value;
}

function settledBody(result, fallback = {}) {
  if (!result || result.status !== "fulfilled") {
    return fallback;
  }
  return result.value === null || result.value === undefined ? fallback : result.value;
}

function normalizeAlarmData(body) {
  if (Array.isArray(body)) {
    return { status: "success", count: body.length, data: body };
  }
  if (!body || typeof body !== "object") {
    return { status: "unavailable", count: 0, data: [] };
  }
  if (body.data && !Array.isArray(body.data) && typeof body.data === "object" && (body.data.status || Array.isArray(body.data.data))) {
    return body.data;
  }
  return body;
}

function safeArray(value) {
  return Array.isArray(value) ? value : [];
}

function objectEntries(value) {
  return value && typeof value === "object" && !Array.isArray(value) ? Object.entries(value) : [];
}

function numberValue(value, fallback = 0) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function percentageRatio(value) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed < 0 || parsed > 100) {
    return null;
  }
  return parsed / 100;
}

function pickValue(source, keys, fallback = "-") {
  if (!source || !Array.isArray(keys)) {
    return fallback;
  }
  for (const key of keys) {
    const value = source[key];
    if (value !== null && value !== undefined && value !== "") {
      return value;
    }
  }
  return fallback;
}

function cloudStatusLabel(status) {
  const normalized = String(status || "UNKNOWN").toUpperCase();
  if (normalized === "OK") {
    return "正常";
  }
  if (normalized === "READY") {
    return "就绪";
  }
  if (normalized === "WARN") {
    return "风险";
  }
  if (normalized === "ERROR") {
    return "异常";
  }
  if (normalized === "DISABLED") {
    return "未启用";
  }
  return "未知";
}

function homeTone(status) {
  const normalized = String(status || "UNKNOWN").toUpperCase();
  if (["OK", "UP", "ONLINE", "SUCCESS", "GOOD"].includes(normalized)) {
    return "green";
  }
  if (normalized === "READY") {
    return "teal";
  }
  if (["WARN", "WARNING", "DEGRADED"].includes(normalized)) {
    return "orange";
  }
  if (["ERROR", "DOWN", "FAILED", "DANGER"].includes(normalized)) {
    return "red";
  }
  if (normalized === "DISABLED") {
    return "muted";
  }
  return "muted";
}

function statusTone(status) {
  const normalized = String(status || "UNKNOWN").toUpperCase();
  if (["OK", "UP", "ONLINE", "SUCCESS", "GOOD"].includes(normalized)) {
    return "ok";
  }
  if (normalized === "READY") {
    return "info";
  }
  if (["WARN", "WARNING", "DEGRADED"].includes(normalized)) {
    return "warn";
  }
  if (["ERROR", "DOWN", "FAILED", "DANGER", "OFFLINE"].includes(normalized)) {
    return "error";
  }
  if (normalized === "DISABLED") {
    return "muted";
  }
  return "info";
}

function setHomeBadge(selector, text, tone = "info") {
  const target = $(selector);
  if (!target) {
    return;
  }
  target.textContent = text;
  target.className = `home-panel-badge is-${tone}`;
}

function renderHomeDashboard(data) {
  renderHomeAlarmCenter(data.alarmData, data.errorData);
  renderHomeRiskDevices(data.deviceData, data.errorData, data.perfDetail, data.availability);
  renderHomePipeline(data);
  renderHomeResources(data.systemData, data.reportData, data.availability);
}

function renderHomeAlarmCenter(alarmData, errorData) {
  const target = $("#homeAlarmRows");
  if (!target) {
    return;
  }
  if (alarmData?.status === "disabled") {
    setHomeBadge("#homeAlarmSummary", "未启用", "muted");
    target.innerHTML = `<div class="empty-state compact">TDengine 告警历史存储未启用，当前只能从异常统计判断风险。</div>`;
    return;
  }
  if (alarmData?.status === "error") {
    setHomeBadge("#homeAlarmSummary", "查询失败", "error");
    target.innerHTML = `<div class="empty-state compact">${escapeHtml(alarmData.message || "告警历史查询失败")}</div>`;
    return;
  }
  if (alarmData?.status === "unavailable") {
    setHomeBadge("#homeAlarmSummary", "数据不可用", "muted");
    target.innerHTML = `<div class="empty-state compact">全局告警接口暂不可用，其他运行指标仍正常展示。</div>`;
    return;
  }

  const rows = safeArray(alarmData?.data).slice(0, 8);
  const total = numberValue(alarmData?.total ?? alarmData?.count, rows.length);
  setHomeBadge("#homeAlarmSummary", rows.length ? `显示 ${rows.length} / 共 ${total} 条` : "无新告警", rows.length ? "warn" : "ok");
  if (!rows.length) {
    const exceptionCount = numberValue(errorData?.totalExceptions ?? errorData?.totalCount ?? errorData?.totalErrors, 0);
    target.innerHTML = `<div class="empty-state compact">最近 24 小时没有告警历史记录${exceptionCount ? `，但异常统计累计 ${exceptionCount} 次` : ""}。</div>`;
    return;
  }

  target.innerHTML = rows.map((row) => {
    const level = pickValue(row, ["alarm_level", "alarmLevel", "level"], "INFO");
    const tone = alarmLevelTone(level);
    const deviceId = pickValue(row, ["device_id", "deviceId"], "-");
    const deviceName = pickValue(row, ["device_name", "deviceName"], deviceId);
    const pointCode = pickValue(row, ["point_code", "pointCode", "point_id", "pointId"], "-");
    const message = pickValue(row, ["message", "rule_name", "ruleName"], "告警触发");
    const ts = pickValue(row, ["event_ts", "eventTs", "ts", "timestamp"], null);
    return `
      <div class="home-event-row is-${tone}">
        <div class="home-event-main">
          <strong>${escapeHtml(message)}</strong>
          <span>${escapeHtml(deviceName)} / ${escapeHtml(pointCode)}</span>
        </div>
        <div class="home-event-meta">
          <b>${escapeHtml(localizeAlarmLevel(level))}</b>
          <span>${escapeHtml(formatTs(ts))}</span>
        </div>
      </div>`;
  }).join("");
}

function localizeAlarmLevel(level) {
  switch (String(level || "").toUpperCase()) {
    case "CRITICAL":
    case "FATAL":
    case "HIGH":
      return "严重";
    case "ERROR":
      return "错误";
    case "WARN":
    case "WARNING":
    case "MEDIUM":
      return "警告";
    case "INFO":
      return "信息";
    default:
      return level || "未知";
  }
}
function alarmLevelTone(level) {
  const normalized = String(level || "").toUpperCase();
  if (["CRITICAL", "FATAL", "ERROR", "HIGH", "严重"].includes(normalized)) {
    return "error";
  }
  if (["WARN", "WARNING", "MEDIUM", "中等", "警告"].includes(normalized)) {
    return "warn";
  }
  return "info";
}

function renderHomeRiskDevices(deviceData, errorData, perfDetail, availability) {
  const target = $("#homeRiskRows");
  if (!target) {
    return;
  }
  const riskMap = new Map();
  const upsertRisk = (deviceId, title, detail, tone, weight) => {
    const id = String(deviceId || "unknown");
    const current = riskMap.get(id);
    if (!current || weight > current.weight) {
      riskMap.set(id, { deviceId: id, title, detail, tone, weight });
    }
  };

  if (availability.devices) {
    safeArray(deviceData?.missingConnections).forEach((deviceId) => {
      upsertRisk(deviceId, "连接缺失", "配置存在但运行连接未建立", "error", 90);
    });

    safeArray(deviceData?.connections).forEach((connection) => {
      const deviceId = connection.deviceId || connection.id || connection.deviceName;
      if (!deviceId) {
        return;
      }
      if (connection.connected === false) {
        upsertRisk(deviceId, "设备离线", `当前状态 ${localizeDeviceStatus(connection.status || "UNKNOWN")}`, "error", 85);
      }
      const errors = numberValue(connection.errors, 0);
      if (errors > 0) {
        upsertRisk(deviceId, "连接异常", `累计错误 ${errors} 次`, "warn", 70 + Math.min(errors, 10));
      }
      const successRate = percentageRatio(connection.successRate);
      if (successRate !== null && successRate > 0 && successRate < 0.98) {
        upsertRisk(deviceId, "成功率偏低", `成功率 ${percent(successRate)}`, "warn", 68);
      }
      const idleTime = numberValue(connection.idleTime, 0);
      if (idleTime > 10 * 60 * 1000) {
        upsertRisk(deviceId, "长时间无活动", `空闲 ${formatDurationMs(idleTime)}`, "warn", 60);
      }
    });
  }

  if (availability.errors) {
    objectEntries(errorData?.byDevice).forEach(([deviceId, count]) => {
      const total = numberValue(count, 0);
      if (total > 0) {
        upsertRisk(deviceId, "异常累计偏高", `异常 ${total} 次`, total >= 10 ? "error" : "warn", 72 + Math.min(total, 20));
      }
    });
  }

  if (availability.performance) {
    objectEntries(perfDetail?.slowestDevices)
      .sort((a, b) => numberValue(b[1]) - numberValue(a[1]))
      .slice(0, 5)
      .forEach(([deviceId, cost]) => {
        const costMs = numberValue(cost, 0);
        if (costMs > 0) {
          upsertRisk(deviceId, "采集耗时较高", `最近耗时 ${costMs} ms`, costMs > 1000 ? "warn" : "info", 50 + Math.min(costMs / 100, 15));
        }
      });
  }

  const unavailableSources = [
    ["设备", availability.devices],
    ["异常", availability.errors],
    ["性能", availability.performance]
  ].filter(([, available]) => !available).map(([label]) => label);
  const allRows = Array.from(riskMap.values()).sort((a, b) => b.weight - a.weight);
  const rows = allRows.slice(0, 6);
  const incomplete = unavailableSources.length > 0;
  setHomeBadge(
    "#homeRiskSummary",
    allRows.length ? `风险 ${allRows.length}${incomplete ? " · 数据不完整" : ""}` : (incomplete ? "数据不完整" : "健康"),
    allRows.length ? "warn" : (incomplete ? "muted" : "ok")
  );
  if (!rows.length) {
    target.innerHTML = incomplete
      ? `<div class="empty-state compact">${escapeHtml(unavailableSources.join("、"))}监控数据不可用，暂时无法判定设备风险。</div>`
      : `<div class="empty-state compact">当前没有连接缺失、慢设备或异常热点。</div>`;
    return;
  }
  target.innerHTML = rows.map((item) => `
    <div class="home-risk-row is-${item.tone}">
      <span class="risk-dot"></span>
      <div>
        <strong>${escapeHtml(item.deviceId)}</strong>
        <p>${escapeHtml(item.title)} · ${escapeHtml(item.detail)}</p>
      </div>
    </div>`).join("");
}

function renderHomePipeline(data) {
  const target = $("#homePipeline");
  if (!target) {
    return;
  }
  const deviceData = data.deviceData || {};
  const perfDetail = data.perfDetail || {};
  const cache = data.cache || {};
  const reportData = data.reportData || {};
  const storageData = data.storageData || {};
  const availability = data.availability || {};
  const missingCount = availability.devices ? safeArray(deviceData.missingConnections).length : null;
  const dangerDevices = availability.devices ? numberValue(deviceData.dangerDevices, 0) : null;
  const warningDevices = availability.devices ? numberValue(deviceData.warningDevices, 0) : null;
  const expectedConnections = availability.devices ? numberValue(deviceData.expectedConnections, 0) : null;
  const activeConnections = availability.devices ? numberValue(deviceData.activeConnections, 0) : null;
  const processRejected = availability.performance
    ? numberValue(perfDetail.batchDispatchRejectedCount, 0)
      + numberValue(perfDetail.collectRejectedCount, 0)
      + numberValue(perfDetail.processRejectedCount, 0)
    : null;
  const overloadedCount = availability.performance ? objectEntries(perfDetail.overloadedSlices).length : null;
  const cloudStatus = availability.report ? reportData.status || "UNKNOWN" : "UNKNOWN";
  const storageStatus = availability.storage ? String(storageData.status || "UNKNOWN").toUpperCase() : "UNKNOWN";
  const redisLevel = availability.cache
    ? safeArray(cache.health?.levels).find((level) => String(level?.type || "").toUpperCase() === "REDIS")
    : null;
  const redisStatus = !availability.cache
    ? "UNKNOWN" : (redisLevel ? String(redisLevel.status || "UNKNOWN").toUpperCase() : "DISABLED");

  setHomeBadge(
    "#homeReportSummary",
    availability.report ? reportData.statusText || cloudStatusLabel(cloudStatus) : "上报数据不可用",
    availability.report ? statusTone(cloudStatus) : "muted"
  );
  const collectorTone = !availability.devices
    ? "muted" : (dangerDevices > 0 || missingCount > 0 ? "error" : (warningDevices > 0 ? "warn" : "ok"));
  const runningTone = !availability.running ? "muted" : (data.runningDevices > 0 ? "ok" : "muted");
  const gatewayTone = !availability.performance
    ? "muted" : (processRejected > 0 ? "error" : (overloadedCount > 0 ? "warn" : "ok"));
  const cacheTone = redisStatus === "HEALTHY" ? "ok" : (redisStatus === "DISABLED" || redisStatus === "UNKNOWN" ? "muted" : "error");
  const historyTone = storageStatus === "OK" ? "ok" : (storageStatus === "DISABLED" || storageStatus === "UNKNOWN" ? "muted" : "error");
  const cloudTone = availability.report ? statusTone(cloudStatus) : "muted";
  const collectorDetail = availability.devices
    ? `${activeConnections}/${expectedConnections} 已连接` : "连接监控数据不可用";
  const gatewayDetail = !availability.performance
    ? "处理性能数据不可用" : (processRejected > 0 ? `${processRejected} 次拒绝` : (overloadedCount > 0 ? `${overloadedCount} 个时间片过载` : "处理指标正常"));
  const cacheDetail = redisStatus === "HEALTHY"
    ? "Redis 连接正常" : (redisStatus === "DISABLED" ? "Redis 未启用" : (redisStatus === "UNKNOWN" ? "Redis 状态未知" : "Redis 连接异常"));
  const historyDetail = availability.storage ? storageData.message || "TDengine 状态未知" : "TDengine 状态不可用";
  const nodeIdentity = data.summary?.serviceId || "节点未配置";
  target.innerHTML = `
    <div class="topology-flow" aria-label="数据采集与上报拓扑">
      <div class="topology-node is-${collectorTone}" title="${escapeAttr(collectorDetail)}">
        <span class="topology-icon"><img src="icons/factory.svg" alt=""></span>
        <strong>采集器</strong>
        <span class="topology-status-dots"><i class="is-${runningTone}"></i><i class="is-${collectorTone}"></i></span>
      </div>
      <span class="topology-connector" aria-hidden="true"></span>
      <div class="topology-node is-gateway is-${gatewayTone}" title="${escapeAttr(gatewayDetail)}">
        <span class="topology-icon"><img src="icons/router-wireless.svg" alt=""></span>
        <strong>边缘网关</strong>
        <small>${escapeHtml(nodeIdentity)}</small>
      </div>
      <span class="topology-connector" aria-hidden="true"></span>
      <div class="topology-storage-stack">
        <div class="topology-storage-pill" title="${escapeAttr(cacheDetail)}">
          <img src="icons/database-cog.svg" alt=""><span>Redis 缓存</span><i class="status-dot is-${cacheTone}"></i>
        </div>
        <div class="topology-storage-pill" title="${escapeAttr(historyDetail)}">
          <img src="icons/database-cog.svg" alt=""><span>TDengine</span><i class="status-dot is-${historyTone}"></i>
        </div>
      </div>
      <span class="topology-connector" aria-hidden="true"></span>
      <div class="topology-node is-${cloudTone}" title="${escapeAttr(availability.report ? reportData.statusText || cloudStatusLabel(cloudStatus) : "上报状态不可用")}">
        <span class="topology-icon"><img src="icons/cloud-upload.svg" alt=""></span>
        <strong>云平台</strong>
        <small>${escapeHtml(availability.report ? cloudStatusLabel(cloudStatus) : "未知")}</small>
      </div>
    </div>`;
}

function renderHomeResources(systemData, reportData, availability) {
  const target = $("#homeResourceRows");
  if (!target) {
    return;
  }
  const systemAvailable = Boolean(availability?.system);
  const reportAvailable = Boolean(availability?.report);
  const heapUsed = systemAvailable ? numberValue(systemData?.heapUsed, -1) : -1;
  const heapMax = systemAvailable ? numberValue(systemData?.heapMax, -1) : -1;
  const heapRate = heapUsed >= 0 && heapMax > 0 ? heapUsed / heapMax : null;
  const totalPhysicalMemory = systemAvailable ? numberValue(systemData?.totalPhysicalMemorySize, -1) : -1;
  const freePhysicalMemory = systemAvailable ? numberValue(systemData?.freePhysicalMemorySize, -1) : -1;
  const memoryRate = totalPhysicalMemory > 0 && freePhysicalMemory >= 0
    ? 1 - freePhysicalMemory / totalPhysicalMemory
    : null;
  const cpu = systemAvailable ? percentageRatio(systemData?.systemCpuLoad) : null;
  const reportExecutor = reportAvailable ? reportData?.executor || {} : {};
  const threadPools = systemAvailable ? systemData?.threadPools || {} : {};
  const poolRows = Object.entries(threadPools)
    .filter(([, value]) => value && numberValue(value.corePoolSize, -1) >= 0);
  const useReportFallback = reportAvailable && !threadPools.reportExecutor && numberValue(reportExecutor.maxPoolSize, -1) >= 0;
  const poolMetricsAvailable = poolRows.length > 0 || useReportFallback;
  const activeThreads = poolMetricsAvailable
    ? poolRows.reduce((total, [, pool]) => total + Math.max(0, numberValue(pool.activeCount, 0)), 0)
      + (useReportFallback ? Math.max(0, numberValue(reportExecutor.activeCount, 0)) : 0)
    : null;
  const queuedTasks = poolMetricsAvailable
    ? poolRows.reduce((total, [, pool]) => total + Math.max(0, numberValue(pool.queueSize, 0)), 0)
      + (useReportFallback ? Math.max(0, numberValue(reportExecutor.queueSize, 0)) : 0)
    : null;
  const rejectedTasks = poolMetricsAvailable
    ? poolRows.reduce((total, [, pool]) => total + Math.max(0, numberValue(pool.rejectedCount, 0)), 0)
      + (useReportFallback ? Math.max(0, numberValue(reportExecutor.rejectedCount, 0)) : 0)
    : null;
  const maxThreads = poolMetricsAvailable
    ? poolRows.reduce((total, [, pool]) => total + Math.max(0, numberValue(pool.maxPoolSize, 0)), 0)
      + (useReportFallback ? Math.max(0, numberValue(reportExecutor.maxPoolSize, 0)) : 0)
    : null;
  const threadUsage = activeThreads !== null && maxThreads > 0 ? activeThreads / maxThreads : null;

  setHomeBadge("#homeResourceSummary", cpu !== null ? `CPU ${percent(cpu)}` : "资源未知", cpu === null ? "muted" : (cpu >= 0.80 ? "warn" : "ok"));
  target.innerHTML = `
    <div class="resource-dashboard">
      <div class="resource-gauges">
        ${resourceGauge("CPU 使用率", cpu, "blue")}
        ${resourceGauge("内存使用率", memoryRate, "orange")}
        ${resourceGauge("JVM 堆内存", heapRate, "green")}
      </div>
      <div class="resource-runtime-summary" title="累计拒绝 ${metricValue(rejectedTasks)} 次，JVM 线程 ${systemAvailable ? systemData?.threadCount ?? "-" : "-"} 个">
        <div><span>活跃线程:</span><strong>${metricValue(activeThreads)} / ${maxThreads !== null && maxThreads > 0 ? maxThreads : "-"}</strong></div>
        <div><span>队列积压:</span><strong class="${queuedTasks !== null && queuedTasks > 0 ? "is-warn" : ""}">${metricValue(queuedTasks)}</strong></div>
        <div class="resource-load-track"><i style="width:${threadUsage === null ? 0 : Math.round(Math.max(0, Math.min(1, threadUsage)) * 100)}%"></i></div>
      </div>
    </div>`;
}

function resourceGauge(label, ratio, tone) {
  const normalized = ratio === null || ratio === undefined ? null : Math.max(0, Math.min(1, numberValue(ratio, 0)));
  const degrees = normalized === null ? 0 : Math.round(normalized * 360);
  const value = normalized === null ? "-" : percent(normalized);
  return `
    <div class="resource-gauge">
      <div class="resource-ring is-${tone}" style="--resource-progress:${degrees}deg">
      </div>
      <span>${escapeHtml(label)}</span>
      <strong>${escapeHtml(value)}</strong>
    </div>`;
}

function resourceSummary(label, value, detail, tone = "") {
  return `
    <div class="resource-summary${tone ? ` is-${tone}` : ""}">
      <span>${escapeHtml(label)}</span>
      <strong>${escapeHtml(value)}</strong>
      <small>${escapeHtml(detail)}</small>
    </div>`;
}

function metricRow(label, value, detail, tone = "info") {
  return `
    <div class="home-metric-row is-${tone}">
      <span>${escapeHtml(label)}</span>
      <strong>${escapeHtml(value)}</strong>
      <small>${escapeHtml(detail)}</small>
    </div>`;
}

function formatDurationMs(value) {
  const ms = numberValue(value, -1);
  if (ms < 0) {
    return "-";
  }
  if (ms < 1000) {
    return `${ms} ms`;
  }
  if (ms < 60 * 1000) {
    return `${(ms / 1000).toFixed(1)} 秒`;
  }
  if (ms < 60 * 60 * 1000) {
    return `${(ms / 60000).toFixed(1)} 分钟`;
  }
  return `${(ms / 3600000).toFixed(1)} 小时`;
}
async function loadDevices() {
  setDeviceListState("正在加载设备列表...", "info");
  const [devicesBody, runtimeBody] = await Promise.allSettled([
    callApi("/api/config/devices"),
    callApi("/api/device/runtime")
  ]);
  const runtime = runtimeBody.status === "fulfilled" ? dataOf(runtimeBody.value) : [];
  state.runtimeStatus = buildUnifiedRuntimeStatusMap(Array.isArray(runtime) ? runtime : []);

  if (devicesBody.status !== "fulfilled") {
    state.devices = [];
    renderDevices();
    setDeviceListState(devicesBody.reason?.message || "设备列表加载失败", "error");
    throw devicesBody.reason;
  }

  const payload = dataOf(devicesBody.value) || {};
  state.devices = Array.isArray(payload.devices) ? payload.devices : [];
  renderDevices();
  fillDeviceSelects();
}

function renderDevices() {
  const currentDeviceId = selectedDeviceId();
  const filteredDevices = state.devices.filter((device) => matchesDeviceSearch(device));
  updateDeviceSearchMeta(filteredDevices.length, state.devices.length);

  if (!state.devices.length) {
    $("#deviceRows").innerHTML = `<tr class="device-empty-row"><td><div class="empty-state">暂无设备配置</div></td></tr>`;
    setDeviceListState("");
    return;
  }
  if (!filteredDevices.length) {
    $("#deviceRows").innerHTML = `<tr class="device-empty-row"><td><div class="empty-state">没有匹配的设备</div></td></tr>`;
    setDeviceListState("");
    return;
  }

  setDeviceListState("");
  const rows = filteredDevices.map((device) => {
    const id = device.id || device.deviceId;
    const address = [device.ipAddress, device.port].filter(Boolean).join(":") || "-";
    const local = isLocalDevice(device);
    const runtime = getRuntimeStatus(id);
    const status = resolveDeviceStatus(device, runtime);
    const statusLabel = localizeDeviceStatus(status);
    const sourceLabel = local ? "本地临时" : "远端同步";
    const selected = currentDeviceId === id;
    const editButton = local
      ? `<button onclick="editLocalDevice('${escapeAttr(id)}')">编辑</button>`
      : `<button onclick="openDeviceRoute('${escapeAttr(id)}', 'collect')">编辑</button>`;
    const deleteButton = local
      ? `<button onclick="deleteLocalDevice('${escapeAttr(id)}')" class="danger">删除</button>`
      : "";
    return `
      <tr>
        <td class="device-card-cell">
          <div class="device-card ${selected ? "is-active" : ""}">
            <button type="button" class="device-card-selector" onclick="selectDevice('${escapeAttr(id)}')">
              <div class="device-card-head">
                <div>
                  <div class="device-card-title">
                    <span class="device-status-dot ${status === "ONLINE" || status === "RUNNING" ? "online" : "offline"}"></span>
                    <strong>${escapeHtml(device.deviceName || id)}</strong>
                  </div>
                  <div class="device-card-subtitle">${escapeHtml(id)} · ${escapeHtml(sourceLabel)}</div>
                </div>
                <span class="badge ${local ? "badge-local" : "badge-remote"}">${escapeHtml(statusLabel)}</span>
              </div>
              <div class="device-card-meta">
                <span>协议 ${escapeHtml(device.protocolType || device.connectionType || "-")}</span>
                <span>地址 ${escapeHtml(address)}</span>
                <span>周期 ${device.collectionInterval ?? "-"} ms</span>
              </div>
            </button>
            <div class="inline-actions device-card-actions">
              <button onclick="startDevice('${escapeAttr(id)}')">启动</button>
              <button onclick="stopDevice('${escapeAttr(id)}')" class="danger">停止</button>
              <button onclick="showDeviceStatus('${escapeAttr(id)}')">状态</button>
              ${editButton}
              <button onclick="showDiff('${escapeAttr(id)}')">差异</button>
              <button onclick="openDeviceRoute('${escapeAttr(id)}', 'control')">控制</button>
              <button onclick="openDeviceRoute('${escapeAttr(id)}', 'shadow')">影子</button>
              ${deleteButton}
            </div>
          </div>
        </td>
      </tr>`;
  }).join("");
  $("#deviceRows").innerHTML = rows;
}

function isLocalDevice(device) {
  return device && (device.configSource === "local" || device.temporaryConfig === true);
}

function fillDeviceSelects() {
  const options = state.devices.map((device) => {
    const id = device.id || device.deviceId;
    const source = isLocalDevice(device) ? "本地" : "同步";
    return `<option value="${escapeAttr(id)}">${escapeHtml(device.deviceName || id)} (${escapeHtml(id)} / ${source})</option>`;
  }).join("");

  ["#connectionDeviceSelect", "#realtimeDeviceSelect", "#controlDeviceSelect", "#shadowDeviceSelect"].forEach((selector) => {
    const select = $(selector);
    if (!select) {
      return;
    }
    select.innerHTML = options;
  });

  const resolvedDeviceId = resolveAvailableDeviceId(state.selectedDeviceId);
  if (!resolvedDeviceId) {
    state.selectedDeviceId = "";
    syncDeviceSelectValues("");
    syncSelectedDeviceSummary("");
    state.realtimeRawPoints = [];
    renderDevices();
    renderRealtimeTable();
    syncRealtimeTimer();
    return;
  }

  syncDeviceContext(resolvedDeviceId, { loadRealtime: false });
  requestRealtimeRefresh();
}

function getProtocolSchema(protocolCode) {
  const canonical = canonicalProtocolForUi(protocolCode);
  return state.protocols.find((item) => item.protocol === canonical) || null;
}

function groupTitle(group) {
  switch (group) {
    case "connection":
      return "连接参数";
    case "protocol":
      return "协议参数";
    case "security":
      return "安全参数";
    case "advanced":
      return "高级参数";
    case "topic":
      return "主题参数";
    case "request":
      return "请求参数";
    case "bridge":
      return "桥接参数";
    default:
      return "字段参数";
  }
}

function renderProtocolMetaTrigger(protocol, triggerLabel = "协议说明") {
  return `
    <span class="field-help protocol-meta-trigger">
      <button type="button" class="field-help-trigger protocol-help-trigger" aria-label="${escapeAttr(triggerLabel)}" title="${escapeAttr(triggerLabel)}">?</button>
      <span class="field-help-popover protocol-help-popover" role="tooltip">${renderProtocolMeta(protocol)}</span>
    </span>
  `;
}

function updateProtocolMetaHelp(targetSelector, protocol, triggerLabel = "协议说明") {
  const target = $(targetSelector);
  if (!target) {
    return;
  }
  target.innerHTML = renderProtocolMetaTrigger(protocol, triggerLabel);
}

function renderProtocolMeta(protocol) {
  if (!protocol) {
    return "<p>暂无协议说明</p>";
  }
  const capabilityLabels = {
    SUPPORTED: "支持",
    UNSUPPORTED: "不支持",
    RUNTIME_DEPENDENT: "依赖运行环境",
    EXPERIMENTAL: "实验性"
  };
  const implementationState = protocol.implementationState
    || (protocol.implemented ? "SUPPORTED" : "UNSUPPORTED");
  const writeCapability = protocol.writeCapability
    || (protocol.writable ? "SUPPORTED" : "UNSUPPORTED");
  const subscriptionCapability = protocol.subscriptionCapability
    || (protocol.subscribable ? "SUPPORTED" : "UNSUPPORTED");
  const browseCapability = protocol.browseCapability || "UNSUPPORTED";
  const status = capabilityLabels[implementationState] || implementationState;
  const aliases = (protocol.aliases || []).map(escapeHtml).join(", ") || "-";
  const addressHints = (protocol.pointAddressHints || []).map((item) => `<code>${escapeHtml(item)}</code>`).join(" ") || "-";
  const dataTypes = (protocol.dataTypes || []).map((item) => `<code>${escapeHtml(item)}</code>`).join(" ") || "-";
  const driverDataTypes = (protocol.driverDataTypes || []).map((item) => `<code>${escapeHtml(item)}</code>`).join(" ") || "-";
  const pointFields = Array.isArray(protocol.pointFields) ? protocol.pointFields : [];
  const typeModeLabel = {
    PLATFORM_ONLY: "平台统一类型主导",
    DRIVER_PRIMARY: "协议原生类型主导",
    PROTOCOL_FIELD_PRIMARY: "协议专属字段主导"
  }[protocol.typeMode] || (protocol.typeMode || "-");
  const platformDataTypeModeLabel = {
    REQUIRED: "必须显式填写",
    DERIVED_EDITABLE: "可推导，且允许人工覆盖",
    DERIVED_READONLY: "自动推导，只读展示",
    ADVANCED: "高级区展示"
  }[protocol.platformDataTypeMode] || (protocol.platformDataTypeMode || "-");
  const driverTypeHtml = protocol.driverTypeEnabled
    ? `
      <p><code>driverTypeEnabled</code>：是。含义：当前协议除了统一 <code>dataType</code> 外，还支持单独选择协议原生类型。</p>
      <p><code>driverTypeLabel</code>：${escapeHtml(protocol.driverTypeLabel || "-")}。含义：前端展示给用户看的协议原生类型字段名称。</p>
      <p><code>driverTypeField</code>：<code>${escapeHtml(protocol.driverTypeField || "-")}</code>。含义：协议原生类型写回点位对象时使用的保存路径。</p>
      <p><code>driverDataTypes</code>：${driverDataTypes}。含义：当前协议允许选择的原生类型候选值。</p>
    `
    : `
      <p><code>driverTypeEnabled</code>：否。含义：当前协议没有单独的协议原生类型补充字段。</p>
      <p><code>driverDataTypes</code>：-。含义：当前协议不需要额外的协议原生类型候选列表。</p>
    `;
  const pointFieldsHtml = pointFields.length
    ? `
      <p><code>pointFields</code>：协议专属点位扩展字段。含义：新增/编辑点位时，前端会把这些字段额外展示出来。</p>
      <ul>${pointFields.map((field) => {
        const label = displayFieldLabel(field);
        const description = field.description || "协议扩展字段";
        const storage = field.storage ? `；保存位置：${field.storage}` : "";
        return `<li><code>${escapeHtml(field.name || "-")}</code> / ${escapeHtml(label)}：${escapeHtml(description + storage)}</li>`;
      }).join("")}</ul>
    `
    : '<p><code>pointFields</code>：无。含义：当前协议没有额外的点位扩展字段。</p>';
  const riskNoteHtml = renderProtocolRiskNote(protocol);
  return `
    <strong>${escapeHtml(protocol.title)}</strong>
    <span class="${implementationState === "UNSUPPORTED" ? "status-bad" : "status-good"}">${escapeHtml(status)}</span>
    <p>${escapeHtml(protocol.description || "")}</p>
    ${riskNoteHtml}
    <p>写入能力：${escapeHtml(capabilityLabels[writeCapability] || writeCapability)}；订阅能力：${escapeHtml(capabilityLabels[subscriptionCapability] || subscriptionCapability)}；浏览能力：${escapeHtml(capabilityLabels[browseCapability] || browseCapability)}</p>
    <p>协议别名：${aliases}</p>
    <p>地址示例：${addressHints}</p>
    <p><code>dataTypes</code>：${dataTypes}</p>
    <p><code>typeMode</code>：${escapeHtml(typeModeLabel)}。含义：这个协议的主类型字段到底走平台统一类型、协议原生类型，还是协议专属字段。</p>
    <p><code>primaryTypeField</code>：<code>${escapeHtml(protocol.primaryTypeField || "-")}</code>。含义：前端当前协议真正优先展示和编辑的主类型字段路径。</p>
    <p><code>platformDataTypeMode</code>：${escapeHtml(platformDataTypeModeLabel)}。含义：当前协议里平台统一 <code>dataType</code> 在页面上的处理方式。</p>
    ${driverTypeHtml}
    ${pointFieldsHtml}
  `;
}

function renderProtocolRiskNote(protocol) {
  if (!protocol || protocol.code !== "MITSUBISHI_MC") {
    return "";
  }
  return `
    <div class="usage-note">
      <strong>MC 生产边界</strong>
      <p><code>3E_BINARY</code> 是当前推荐的稳定生产路径。<code>3E_ASCII</code>、<code>4E_BINARY</code> 仍应按分阶段方式上线，先做现场联机和真机报文回放验证，再放量。</p>
      <p><code>randomReadEnabled</code> / <code>randomWriteEnabled</code> 只适合稀疏的标量字点位；连续地址块、批量字符串和数组点仍以常规批读批写链路为主，不应把随机读写当成通用加速开关。</p>
      <p><code>driverDataType=STRING</code> 时必须补 <code>additionalConfig.stringLength</code>。像 <code>D100.3</code> 这样的位偏移写入，当前仅在单采集器进程内做同字串行保护；如果多个进程或外部系统同时改同一字，仍需要上层治理避免互相覆盖。</p>
    </div>
  `;
}

function fieldDefaultValue(field) {
  if (!field) {
    return "";
  }
  if (field.defaultValue !== null && field.defaultValue !== undefined) {
    return field.defaultValue;
  }
  return field.type === "object" ? "{}" : "";
}

function fieldTokenText(field) {
  return `${field?.name || ""} ${field?.label || ""} ${field?.description || ""}`.toLowerCase();
}

function fieldLayoutClass(field) {
  const tokens = fieldTokenText(field);
  const isLongField = Boolean(field?.fullWidth)
    || field?.type === "object"
    || field?.type === "textarea"
    || /(json|template|payload|header|body|certificate|private\s*key|public\s*key|truststore|keystore|nodeid|topic|path|url|uri|endpoint|script|query|string\s*pattern|publish|subscribe)/.test(tokens);
  if (isLongField) {
    return "field-span-2 field-control-lg";
  }
  const isShortField = field?.type === "boolean"
    || field?.type === "select"
    || field?.type === "number"
    || /(port|qos|retry|retries|timeout|interval|namespace|unitid|slaveid|rack|slot|baud|databits|stopbits|parity|mode|type|retain|tls|ssl|enabled|enable|max|min|size|pool|version|method)/.test(tokens);
  return isShortField ? "field-control-sm" : "field-control-md";
}

function renderFieldOption(option, currentValue) {
  const value = option && typeof option === "object" ? option.value : option;
  const label = option && typeof option === "object" ? option.label ?? option.value : option;
  return `<option value="${escapeAttr(value ?? "")}" ${String(value ?? "") === String(currentValue ?? "") ? "selected" : ""}>${escapeHtml(label ?? "")}</option>`;
}

function displayFieldLabel(field) {
  const rawLabel = String(field?.label || field?.name || "");
  const translations = {
    "Device host": "设备主机",
    Host: "主机地址",
    "Byte order": "字节序",
    Parity: "校验位",
    "PLC4X connection string": "PLC4X 连接串",
    "PLC4X ping address": "PLC4X 探测地址",
    "Max registers per request": "单次最大寄存器数",
    "Max coils per request": "单次最大线圈数",
    "Read timeout (ms)": "读取超时（毫秒）",
    "Protocol timeout (ms)": "协议超时（毫秒）",
    "Serial port": "串口号",
    "Baud rate": "波特率",
    "Data bits": "数据位",
    "Stop bits": "停止位",
    "Inter-frame delay (ms)": "帧间隔（毫秒）",
    "PLC4X protocol code": "PLC4X 协议编码",
    Port: "端口",
    COM: "串口号",
    Baud: "波特率",
    Endpoint: "端点地址",
    Broker: "代理地址",
    Username: "用户名",
    Password: "密码",
    ClientId: "客户端标识",
    ClientID: "客户端标识",
    "Client ID": "客户端标识",
    SlaveId: "从站地址",
    "Slave ID": "从站地址",
    UnitId: "单元地址",
    "Unit ID": "单元地址",
    Rack: "机架号",
    Slot: "槽位号",
    Timeout: "超时时间",
    Retry: "重试次数",
    Retries: "重试次数",
    TLS: "TLS 加密",
    SSL: "SSL 加密",
    Topic: "主题",
    "Subscribe Topic": "订阅主题（Topic）",
    "Write Topic": "写入主题（Topic）",
    "Payload Encoding": "载荷编码",
    reportField: "上报属性（reportField）",
    productKey: "产品标识（productKey）",
    deviceName: "设备名称（deviceName）",
    cloudTarget: "云目标（cloudTarget）",
    dataType: "数据类型"
  };
  return translations[rawLabel] || rawLabel;
}
function renderField(field, formId) {
  const required = field.required ? `<span class="field-required">*</span>` : "";
  const hint = field.requiredWhen ? `<span class="field-hint">${escapeHtml(field.requiredWhen)}</span>` : "";
  const labelText = `<span class="field-label-text">${escapeHtml(displayFieldLabel(field))}${required}</span>`;
  const labelRow = `<span class="field-label-row">${labelText}${hint}</span>`;
  const note = fieldHelpText(field);
  const value = fieldDefaultValue(field);
  const inputName = escapeAttr(field.name);
  const labelClass = fieldLayoutClass(field);
  const placeholder = field.placeholder !== null && field.placeholder !== undefined && field.placeholder !== ""
    ? ` placeholder="${escapeAttr(field.placeholder)}"`
    : "";
  const step = field.step !== null && field.step !== undefined && field.step !== ""
    ? ` step="${escapeAttr(field.step)}"`
    : field.type === "number"
      ? ' step="any"'
      : "";
  const min = field.min !== null && field.min !== undefined && field.min !== "" ? ` min="${escapeAttr(field.min)}"` : "";
  const max = field.max !== null && field.max !== undefined && field.max !== "" ? ` max="${escapeAttr(field.max)}"` : "";
  let control;
  if (field.type === "select" || field.type === "boolean") {
    const options = field.options && field.options.length ? field.options : ["true", "false"];
    control = `<select name="${inputName}" data-form-id="${escapeAttr(formId)}">${options.map((option) => renderFieldOption(option, value)).join("")}</select>`;
  } else if (field.type === "object" || field.type === "textarea") {
    const rows = Number(field.rows) > 0 ? Math.max(3, Number(field.rows)) : 4;
    control = `<textarea name="${inputName}" data-form-id="${escapeAttr(formId)}" rows="${rows}"${placeholder}>${escapeHtml(value || (field.type === "object" ? "{}" : ""))}</textarea>`;
  } else {
    const inputType = field.type === "password" ? "password" : field.type === "number" ? "number" : "text";
    control = `<input name="${inputName}" data-form-id="${escapeAttr(formId)}" type="${inputType}" value="${escapeAttr(value)}"${placeholder}${step}${min}${max}>`;
  }
  return `
    <label class="${labelClass}" data-field="${inputName}" data-required="${field.required ? "true" : "false"}" data-required-when="${escapeAttr(field.requiredWhen || "")}">
      ${labelRow}
      ${control}
      ${note ? `<span class="field-description">${escapeHtml(note)}</span>` : ""}
      <span class="field-error hidden"></span>
    </label>`;
}

function renderProtocolForm(containerSelector, protocol, formId) {
  const container = $(containerSelector);
  if (!container) {
    return;
  }
  if (!protocol || !Array.isArray(protocol.connectionFields) || !protocol.connectionFields.length) {
    container.innerHTML = "<p>当前协议没有额外的连接配置字段。</p>";
    return;
  }
  const groups = new Map();
  protocol.connectionFields.forEach((field) => {
    const group = field.group || "fields";
    if (!groups.has(group)) {
      groups.set(group, []);
    }
    groups.get(group).push(field);
  });
  container.innerHTML = Array.from(groups.entries()).map(([group, fields]) => `
    <section class="field-group" data-group="${escapeAttr(group)}">
      <h3>${escapeHtml(groupTitle(group))}</h3>
      ${renderGroupDescription(protocol, group)}
      <div class="dynamic-form">
        ${fields.map((field) => renderField(field, formId)).join("")}
      </div>
    </section>
  `).join("");
  bindConditionalFields(containerSelector, protocol);
}

function evaluateSimpleCondition(containerSelector, condition) {
  const match = condition.match(/^([^=!]+)\s*(!=|=)\s*(.+)$/);
  if (!match) {
    return true;
  }
  const fieldName = match[1].trim();
  const operator = match[2];
  const expected = match[3].trim();
  const input = $(`${containerSelector} [name="${cssEscape(fieldName)}"]`);
  if (!input) {
    return false;
  }
  const actual = String(input.value ?? "").trim();
  if (expected.includes("/")) {
    const candidates = expected.split("/").map((item) => item.trim());
    return operator === "!=" ? !candidates.includes(actual) : candidates.includes(actual);
  }
  return operator === "!=" ? actual !== expected : actual === expected;
}

function conditionMatches(containerSelector, condition) {
  if (!condition) {
    return true;
  }
  return condition
    .split(/\s+or\s+/i)
    .map((item) => item.trim())
    .filter(Boolean)
    .some((item) => evaluateSimpleCondition(containerSelector, item));
}

function bindConditionalFields(containerSelector, protocol) {
  const container = $(containerSelector);
  if (!container || !protocol) {
    return;
  }
  const refresh = () => applyConditionalFields(containerSelector, protocol);
  container.querySelectorAll("[name]").forEach((input) => {
    input.addEventListener("change", refresh);
    input.addEventListener("input", refresh);
  });
  refresh();
}

function applyConditionalFields(containerSelector, protocol) {
  const container = $(containerSelector);
  if (!container || !protocol) {
    return;
  }
  protocol.connectionFields.forEach((field) => {
    const label = container.querySelector(`[data-field="${cssEscape(field.name)}"]`);
    if (!label) {
      return;
    }
    const active = conditionMatches(containerSelector, field.requiredWhen);
    if (field.requiredWhen) {
      label.classList.toggle("hidden", !active);
    }
    label.dataset.active = active ? "true" : "false";
  });
}

function fillProtocolForm(containerSelector, protocol, connection) {
  if (!protocol) {
    return;
  }
  const ext = connection?.extJson || {};
  $(containerSelector)?.querySelectorAll("[name]").forEach((input) => {
    const name = input.name;
    const value = connection?.[name] ?? ext[name];
    if (value === undefined || value === null) {
      return;
    }
    if (input.tagName === "TEXTAREA" && typeof value === "object") {
      input.value = JSON.stringify(value, null, 2);
    } else {
      input.value = String(value);
    }
  });
  applyConditionalFields(containerSelector, protocol);
}

function clearFieldErrors(containerSelector) {
  $(containerSelector)?.querySelectorAll(".field-error").forEach((node) => {
    node.textContent = "";
    node.classList.add("hidden");
  });
}

function setFieldError(label, message) {
  const target = label.querySelector(".field-error");
  if (!target) {
    return;
  }
  if (!message) {
    target.textContent = "";
    target.classList.add("hidden");
    return;
  }
  target.textContent = message;
  target.classList.remove("hidden");
}

function collectProtocolForm(containerSelector, protocol, deviceId) {
  const payload = {
    deviceId,
    connectionType: protocol?.protocol || "",
    extJson: {}
  };
  if (!protocol) {
    return payload;
  }
  clearFieldErrors(containerSelector);
  const errors = [];
  $(containerSelector)?.querySelectorAll("[name]").forEach((input) => {
    const name = input.name;
    const field = protocol.connectionFields.find((item) => item.name === name) || {};
    const label = input.closest("label");
    const active = !label || label.dataset.active !== "false";
    if (!active) {
      return;
    }
    const rawValue = input.value;
    const trimmed = typeof rawValue === "string" ? rawValue.trim() : rawValue;
    const required = Boolean(field.required) || Boolean(field.requiredWhen && active);
    if (required && (trimmed === "" || trimmed === null || trimmed === undefined)) {
      errors.push(`${displayFieldLabel(field)}为必填项`);
      if (label) {
        setFieldError(label, "");
      }
      return;
    }
    let parsed;
    try {
      parsed = parseValue(rawValue, field.type);
    } catch (error) {
      errors.push(`${displayFieldLabel(field)}：${error.message}`);
      if (label) {
        setFieldError(label, "格式错误");
      }
      return;
    }
    if (parsed === "" || parsed === null || parsed === undefined) {
      return;
    }
    if ((field.storage || "extJson") === "topLevel") {
      payload[name] = parsed;
    } else {
      payload.extJson[name] = parsed;
    }
  });
  if (errors.length) {
    const error = new Error(errors[0]);
    error.validationErrors = errors;
    throw error;
  }
  return payload;
}

function openConfiguredLocalDeviceForm(bundle = null) {
  if (window.localDeviceEditor?.open) {
    window.localDeviceEditor.open(bundle);
    return;
  }
  openLocalDeviceForm(bundle);
}

function openLocalDeviceForm(bundle = null) {
  state.localDeviceEditingId = bundle?.device?.id || bundle?.device?.deviceId || null;
  document.querySelector(".local-device-panel")?.classList.remove("hidden");
  $("#localDevicePanel").classList.remove("hidden");
  $("#localEditorBackdrop")?.classList.remove("hidden");
  document.body.classList.add("modal-active");

  const device = bundle?.device || {};
  const connection = bundle?.connection || {};
  const points = bundle?.points || [defaultPointTemplate(device.id || device.deviceId || "local-device")];
  const deviceId = device.id || device.deviceId || "";
  const protocol = canonicalProtocolForUi(
    device.protocolType || connection.connectionType || $("#localProtocolSelect").value || "MODBUS_TCP"
  );
  const adaptive = resolveAdaptiveDefaults(device, points);

  $("#localEditorTitle").textContent = state.localDeviceEditingId ? "编辑本地临时设备" : "新增本地临时设备";
  $("#localDeviceId").value = deviceId;
  $("#localDeviceId").disabled = Boolean(state.localDeviceEditingId);
  $("#localDeviceName").value = device.deviceName || "";
  $("#localProtocolSelect").value = protocol;
  $("#localCollectionInterval").value = adaptive.baseCollectionInterval;
  $("#localMinCollectionInterval").value = adaptive.minCollectionInterval;
  $("#localMaxCollectionInterval").value = adaptive.maxCollectionInterval;
  $("#localPointChangeThreshold").value = adaptive.pointChangeThreshold;
  $("#localStartAfterSave").checked = false;
  $("#localOverwrite").checked = Boolean(state.localDeviceEditingId);
  $("#localPointsJson").value = JSON.stringify(points, null, 2);
  renderLocalProtocolSelection();
  fillProtocolForm("#localConnectionForm", state.currentLocalProtocol, {
    ...connection,
    host: connection.host || device.ipAddress,
    port: connection.port || device.port,
    connectionType: connection.connectionType || protocol
  });
}

function closeLocalDeviceForm() {
  state.localDeviceEditingId = null;
  $("#localDevicePanel").classList.add("hidden");
  document.querySelector(".local-device-panel")?.classList.add("hidden");
  $("#localEditorBackdrop")?.classList.add("hidden");
  document.body.classList.remove("modal-active");
  $("#localDeviceId").disabled = false;
}

function renderLocalProtocolSelection() {
  const protocolCode = canonicalProtocolForUi($("#localProtocolSelect").value || "MODBUS_TCP");
  $("#localProtocolSelect").value = protocolCode;
  state.currentLocalProtocol = getProtocolSchema(protocolCode);
  updateProtocolMetaHelp("#localProtocolMetaHelp", state.currentLocalProtocol, `${state.currentLocalProtocol?.title || protocolCode} 协议说明`);
  renderProtocolForm("#localConnectionForm", state.currentLocalProtocol, "localConnectionForm");
}

function defaultPointTemplate(deviceId) {
  return {
    pointCode: "temperature",
    pointName: "温度",
    deviceId,
    address: "40001",
    dataType: "FLOAT",
    readWrite: "R",
    status: 1,
    cacheEnabled: 1,
    baseCollectionInterval: adaptiveDefaults.baseCollectionInterval,
    currentCollectionInterval: adaptiveDefaults.baseCollectionInterval,
    minCollectionInterval: adaptiveDefaults.minCollectionInterval,
    maxCollectionInterval: adaptiveDefaults.maxCollectionInterval,
    pointChangeThreshold: adaptiveDefaults.pointChangeThreshold,
    additionalConfig: {
      reportEnabled: true,
      reportField: "temperature",
      writeAddress: "C_SE_NC_1:1",
      writeCommonAddress: 1,
      writeSelect: false,
      writeQl: 0
    }
  };
}

function resolveAdaptiveDefaults(device, points) {
  const firstPoint = Array.isArray(points) && points.length ? points[0] : {};
  const base = positiveNumber(firstPoint.baseCollectionInterval)
    || positiveNumber(device.collectionInterval)
    || adaptiveDefaults.baseCollectionInterval;
  return {
    baseCollectionInterval: base,
    minCollectionInterval: positiveNumber(firstPoint.minCollectionInterval)
      || adaptiveDefaults.minCollectionInterval,
    maxCollectionInterval: positiveNumber(firstPoint.maxCollectionInterval)
      || adaptiveDefaults.maxCollectionInterval,
    pointChangeThreshold: positiveNumber(firstPoint.pointChangeThreshold)
      || adaptiveDefaults.pointChangeThreshold
  };
}

function readAdaptiveFormValues() {
  const min = positiveNumber($("#localMinCollectionInterval").value)
    || adaptiveDefaults.minCollectionInterval;
  const max = positiveNumber($("#localMaxCollectionInterval").value)
    || adaptiveDefaults.maxCollectionInterval;
  const normalizedMin = Math.min(min, max);
  const normalizedMax = Math.max(min, max);
  const baseInput = positiveNumber($("#localCollectionInterval").value)
    || adaptiveDefaults.baseCollectionInterval;
  return {
    baseCollectionInterval: Math.max(normalizedMin, Math.min(baseInput, normalizedMax)),
    minCollectionInterval: normalizedMin,
    maxCollectionInterval: normalizedMax,
    pointChangeThreshold: positiveNumber($("#localPointChangeThreshold").value)
      || adaptiveDefaults.pointChangeThreshold
  };
}

function formatLocalPointsJson() {
  try {
    const points = JSON.parse($("#localPointsJson").value || "[]");
    $("#localPointsJson").value = JSON.stringify(Array.isArray(points) ? points : [points], null, 2);
  } catch (error) {
    toast(`JSON 格式错误：${error.message}`, true);
  }
}

function buildLocalDeviceRequest() {
  const deviceId = $("#localDeviceId").value.trim();
  const protocol = canonicalProtocolForUi($("#localProtocolSelect").value || "MODBUS_TCP");
  const connection = collectProtocolForm("#localConnectionForm", state.currentLocalProtocol, deviceId);
  const rawPoints = JSON.parse($("#localPointsJson").value || "[]");
  const adaptive = readAdaptiveFormValues();
  const points = (Array.isArray(rawPoints) ? rawPoints : [rawPoints]).map((point) => ({
    ...point,
    deviceId,
    baseCollectionInterval: adaptive.baseCollectionInterval,
    currentCollectionInterval: adaptive.baseCollectionInterval,
    minCollectionInterval: adaptive.minCollectionInterval,
    maxCollectionInterval: adaptive.maxCollectionInterval,
    pointChangeThreshold: adaptive.pointChangeThreshold,
    additionalConfig: {
      ...(point.additionalConfig || {}),
      configSource: "local",
      temporaryConfig: true
    }
  }));
  const host = connection.host;
  const port = connection.port;

  return {
    device: {
      id: deviceId,
      deviceName: $("#localDeviceName").value.trim(),
      protocolType: protocol,
      connectionType: protocol,
      ipAddress: host || undefined,
      port: port || undefined,
      collectionInterval: adaptive.baseCollectionInterval,
      configSource: "local",
      temporaryConfig: true,
      status: "OFFLINE"
    },
    connection: {
      ...connection,
      deviceId,
      connectionType: connection.connectionType || protocol,
      extJson: {
        ...(connection.extJson || {}),
        configSource: "local",
        temporaryConfig: true
      }
    },
    points,
    overwrite: $("#localOverwrite").checked || Boolean(state.localDeviceEditingId),
    startAfterSave: $("#localStartAfterSave").checked
  };
}

async function saveLocalDevice() {
  const payload = buildLocalDeviceRequest();
  const deviceId = payload.device.id;
  const editing = Boolean(state.localDeviceEditingId);
  await callApi(editing
    ? `/api/config/local/device/${encodeURIComponent(state.localDeviceEditingId)}`
    : "/api/config/local/devices", {
    method: editing ? "PUT" : "POST",
    body: JSON.stringify(payload)
  });
  toast("本地临时设备已保存");
  closeLocalDeviceForm();
  await Promise.all([loadDevices(), loadOverview(), loadMonitor()]);
  if (payload.startAfterSave) {
    await loadDevices();
    await showDeviceStatus(deviceId);
  }
}

async function editLocalDevice(deviceId) {
  const body = await callApi(`/api/config/local/device/${encodeURIComponent(deviceId)}`);
  const payload = dataOf(body);
  openConfiguredLocalDeviceForm(payload.bundle);
}

async function deleteLocalDevice(deviceId) {
  if (!window.confirm(`确认删除本地临时设备 ${deviceId}？该操作不会删除远端配置。`)) {
    return;
  }
  await callApi(`/api/config/local/device/${encodeURIComponent(deviceId)}`, { method: "DELETE" });
  toast("本地临时设备已删除");
  await Promise.all([loadDevices(), loadOverview()]);
}

async function loadProtocols() {
  setProtocolFormState("正在加载协议定义...", "info");
  const body = await callApi("/api/protocols");
  state.protocols = dataOf(body) || [];
  const visibleProtocols = state.protocols.filter((protocol) => !HIDDEN_PROTOCOLS.has(protocol.protocol));
  $("#protocolCount").textContent = `${visibleProtocols.length} 种协议`;
  $("#protocolSelect").innerHTML = visibleProtocols
    .map((protocol) => `<option value="${protocol.protocol}">${protocol.title} (${protocol.protocol})</option>`)
    .join("");
  $("#localProtocolSelect").innerHTML = visibleProtocols
    .map((protocol) => `<option value="${protocol.protocol}">${protocol.title} (${protocol.protocol})</option>`)
    .join("");
  renderLocalProtocolSelection();
  renderSelectedProtocol();
  syncProtocolSelectionToDevice(false);
  syncControlCommandExample();
  setProtocolFormState(visibleProtocols.length ? "" : "当前没有可编辑的协议定义", visibleProtocols.length ? "info" : "warning");
  if (!selectedDeviceId()) {
    setProtocolDiffState("选择设备后查看连接配置差异", "muted");
  }
}

function renderSelectedProtocol() {
  const protocolCode = canonicalProtocolForUi($("#protocolSelect").value);
  $("#protocolSelect").value = protocolCode;
  state.currentProtocol = getProtocolSchema(protocolCode);
  const protocol = state.currentProtocol;
  updateProtocolMetaHelp("#protocolMetaHelp", protocol, `${protocol?.title || protocolCode || "协议"} 协议说明`);
  if (!protocol) {
    $("#connectionForm").innerHTML = "";
    setProtocolFormState("请选择可用协议后再编辑连接参数", "warning");
    return;
  }
  setProtocolFormState("");
  renderProtocolForm("#connectionForm", protocol, "connectionForm");
}

async function loadConnection() {
  const deviceId = $("#connectionDeviceSelect").value;
  if (!deviceId) {
    setProtocolFormState("请先选择设备，再读取连接配置", "warning");
    toast("请先选择设备", true);
    return;
  }
  syncProtocolSelectionToDevice(false);
  setProtocolFormState("正在读取连接配置...", "info");
  const body = await callApi(`/api/config/device/${encodeURIComponent(deviceId)}/connection`);
  const connection = dataOf(body).connection || {};
  fillProtocolForm("#connectionForm", state.currentProtocol, connection);
  setProtocolFormState("");
  await loadDeviceDiff();
  toast("连接配置已读取");
}

async function saveConnection() {
  const deviceId = $("#connectionDeviceSelect").value;
  const device = getDeviceById(deviceId);
  const protocol = deviceProtocolCode(device);
  if (!deviceId || !protocol) {
    setProtocolFormState("请选择设备并确认协议类型", "warning");
    toast("请选择设备并确认协议类型", true);
    return;
  }
  if ($("#protocolSelect").value !== protocol) {
    $("#protocolSelect").value = protocol;
    renderSelectedProtocol();
  }
  setProtocolFormState("正在保存连接配置...", "info");
  const payload = collectProtocolForm("#connectionForm", state.currentProtocol, deviceId);
  payload.connectionType = protocol;
  await callApi(`/api/config/device/${encodeURIComponent(deviceId)}/connection`, {
    method: "PUT",
    body: JSON.stringify(payload)
  });
  setProtocolFormState("连接配置已保存", "success");
  await loadDeviceDiff();
  toast("连接配置已保存");
}

function parseValue(value, type) {
  const trimmed = typeof value === "string" ? value.trim() : value;
  if (type === "number") {
    return trimmed === "" ? null : Number(trimmed);
  }
  if (type === "boolean") {
    return trimmed === "true";
  }
  if (type === "object") {
    if (!trimmed) {
      return {};
    }
    return JSON.parse(trimmed);
  }
  return trimmed;
}

function positiveNumber(value) {
  const number = Number(value);
  return Number.isFinite(number) && number > 0 ? number : null;
}

async function loadDeviceDiff() {
  const deviceId = $("#connectionDeviceSelect").value;
  if (!deviceId) {
    $("#diffView").textContent = "请选择设备查看配置差异";
    setProtocolDiffState("选择设备后查看连接配置差异", "muted");
    return;
  }
  syncProtocolSelectionToDevice(false);
  setProtocolDiffState("正在比对本地与远端配置...", "info");
  const body = await callApi(`/api/config/device/${encodeURIComponent(deviceId)}/diff`);
  $("#diffView").textContent = JSON.stringify(dataOf(body), null, 2);
  setProtocolDiffState("");
}

async function startDevice(deviceId) {
  const device = state.devices.find((item) => (item.id || item.deviceId) === deviceId);
  const action = isLocalDevice(device) ? "start-local" : "start";
  await callApi(`/api/device/${encodeURIComponent(deviceId)}/${action}`, { method: "POST" });
  await Promise.all([loadDevices(), loadOverview(), loadMonitor()]);
  toast(`已请求启动设备 ${deviceId}`);
}

async function stopDevice(deviceId) {
  await callApi(`/api/device/${encodeURIComponent(deviceId)}/stop`, { method: "POST" });
  await Promise.all([loadDevices(), loadOverview(), loadMonitor()]);
  toast(`已请求停止设备 ${deviceId}`);
}

async function showDeviceStatus(deviceId) {
  const body = await callApi(`/api/device/${encodeURIComponent(deviceId)}/status`);
  $("#monitorView").textContent = JSON.stringify(body, null, 2);
  location.hash = "#monitor";
}

async function showDiff(deviceId) {
  selectDevice(deviceId);
  $("#connectionDeviceSelect").value = deviceId;
  syncProtocolSelectionToDevice(false);
  activateWorkbenchTab("protocol");
  await loadDeviceDiff();
  location.hash = "#protocols";
}

async function reloadDevices() {
  await callApi("/api/device/reload", { method: "POST" });
  await Promise.all([loadDevices(), loadOverview(), loadMonitor()]);
  toast("已触发重载所有设备");
}

async function exportConfig() {
  const body = await callApi("/api/config/export");
  downloadJson("collector-config-export.json", dataOf(body));
}

async function syncConfig() {
  await callApi("/api/config/sync", { method: "POST" });
  toast("已触发配置同步");
}

function toggleRealtime() {
  state.realtimeAutoRefreshEnabled = !state.realtimeAutoRefreshEnabled;
  syncRealtimeTimer();
  if (state.realtimeAutoRefreshEnabled) {
    requestRealtimeRefresh();
  }
}

async function loadRealtime() {
  const deviceId = selectedDeviceId();
  if (!deviceId) {
    state.realtimeRawPoints = [];
    state.realtimePoints = [];
    renderRealtimeTable();
    return;
  }

  const requestSeq = ++state.realtimeRequestSeq;
  state.realtimeLoading = true;
  syncSelectedDeviceSummary(deviceId);
  setRealtimeState(state.realtimeRawPoints.length ? "正在刷新实时数据..." : "正在加载实时数据...", "info");

  try {
    const body = await callApi(`/api/data/device/${encodeURIComponent(deviceId)}`);
    if (requestSeq !== state.realtimeRequestSeq || deviceId !== selectedDeviceId()) {
      return;
    }
    const values = dataOf(body) || body.data || {};
    const sourcePoints = Array.isArray(values) ? values : Object.values(values || {});
    state.realtimeRawPoints = sourcePoints.filter(Boolean).map((point) => ({ ...point }));
    renderRealtimeTable();
    setRealtimeState("");
    $("#lastRefresh").textContent = new Date().toLocaleString();
  } catch (error) {
    if (requestSeq === state.realtimeRequestSeq && deviceId === selectedDeviceId()) {
      setRealtimeState(error.message || "实时数据加载失败", "error");
      if (!state.realtimeRawPoints.length) {
        renderRealtimeTable();
      }
    }
    throw error;
  } finally {
    if (requestSeq === state.realtimeRequestSeq) {
      state.realtimeLoading = false;
      if (state.realtimeQueued) {
        state.realtimeQueued = false;
        requestRealtimeRefresh();
      }
    }
  }
}

async function resetAdaptive() {
  const deviceId = selectedDeviceId();
  if (!deviceId) {
    setRealtimeState("请选择设备后再重置自适应参数", "warning");
    toast("请选择设备", true);
    return;
  }
  await callApi(`/api/data/device/${encodeURIComponent(deviceId)}/reset-adaptive`, { method: "POST" });
  setRealtimeState("自适应采集参数已重置", "success");
  toast("自适应采集参数已重置");
}

async function writePoints() {
  try {
    const deviceId = $("#controlDeviceSelect").value;
    if (!deviceId) {
      toast("请选择设备", true);
      return;
    }
    const payload = JSON.parse($("#pointWriteInput").value);
    const body = await callApi(`/api/control/device/${encodeURIComponent(deviceId)}/points`, {
      method: "POST",
      body: JSON.stringify(payload)
    });
    $("#controlView").textContent = JSON.stringify(dataOf(body), null, 2);
    toast("点位写入请求已完成");
  } catch (error) {
    showControlError(error);
  }
}

async function executeCommand() {
  try {
    const deviceId = $("#controlDeviceSelect").value;
    if (!deviceId) {
      toast("请选择设备", true);
      return;
    }
    const payload = JSON.parse($("#commandInput").value);
    const body = await callApi(`/api/control/device/${encodeURIComponent(deviceId)}/command`, {
      method: "POST",
      body: JSON.stringify(payload)
    });
    $("#controlView").textContent = JSON.stringify(dataOf(body), null, 2);
    toast("命令执行请求已完成");
  } catch (error) {
    showControlError(error);
  }
}

function showControlError(error) {
  const result = {
    success: false,
    message: error.message || "操作失败"
  };
  if (error.httpStatus) {
    result.httpStatus = error.httpStatus;
  }
  if (error.body && Object.prototype.hasOwnProperty.call(error.body, "data")) {
    result.data = error.body.data;
  } else if (error.body) {
    result.response = error.body;
  }
  $("#controlView").textContent = JSON.stringify(result, null, 2);
  toast(result.message, true);
}

async function loadShadow() {
  const deviceId = $("#shadowDeviceSelect")?.value || selectedDeviceId();
  if (!deviceId) {
    $("#shadowView").textContent = "请选择设备后查看影子状态";
    setShadowState("请选择设备后查看影子状态", "muted");
    return;
  }
  setShadowState("正在加载影子状态...", "info");
  const body = await callApi(`/api/shadow/${encodeURIComponent(deviceId)}`);
  $("#shadowView").textContent = JSON.stringify(dataOf(body), null, 2);
  setShadowState("");
}

async function saveDesired() {
  const deviceId = $("#shadowDeviceSelect")?.value || selectedDeviceId();
  if (!deviceId) {
    setShadowState("请选择设备后再提交期望状态", "warning");
    toast("请选择设备", true);
    return;
  }
  setShadowState("正在提交期望状态...", "info");
  const payload = JSON.parse($("#desiredInput").value);
  const body = await callApi(`/api/shadow/${encodeURIComponent(deviceId)}/desired`, {
    method: "POST",
    body: JSON.stringify(payload)
  });
  $("#shadowView").textContent = JSON.stringify(dataOf(body), null, 2);
  setShadowState("期望状态已提交", "success");
  toast("期望状态已提交");
}

async function clearDesired() {
  const deviceId = $("#shadowDeviceSelect")?.value || selectedDeviceId();
  if (!deviceId) {
    setShadowState("请选择设备后再清理期望状态", "warning");
    toast("请选择设备", true);
    return;
  }
  setShadowState("正在清理期望状态...", "info");
  const body = await callApi(`/api/shadow/${encodeURIComponent(deviceId)}/desired`, { method: "DELETE" });
  $("#shadowView").textContent = JSON.stringify(dataOf(body), null, 2);
  setShadowState("期望状态已清理", "success");
  toast("期望状态已清理");
}

async function loadMonitor() {
  setMonitorState("正在加载监控视图...", "info");
  const results = await Promise.allSettled([
    callApi("/monitor/cache"),
    callApi("/monitor/devices"),
    callApi("/monitor/performance"),
    callApi("/monitor/system"),
    callApi("/monitor/errors")
  ]);
  const [cache, devices, performance, system, errors] = results;
  const cacheData = cache.status === "fulfilled" ? dataOf(cache.value) : {};
  const deviceData = devices.status === "fulfilled" ? dataOf(devices.value) : {};
  const performanceData = performance.status === "fulfilled" ? dataOf(performance.value) : {};
  const systemData = system.status === "fulfilled" ? dataOf(system.value) : {};
  const errorData = errors.status === "fulfilled" ? dataOf(errors.value) : {};

  renderCards("#monitorCards", [
    { label: "总访问", value: cacheData.totalAccess ?? "-", subtext: `L1 命中 ${percent(cacheData.level1HitRate)}` },
    { label: "活跃连接", value: deviceData.activeConnections ?? "-", subtext: `缺失 ${Array.isArray(deviceData.missingConnections) ? deviceData.missingConnections.length : "-"}` },
    { label: "堆内存", value: bytes(systemData.heapUsed), subtext: `线程 ${systemData.threadCount ?? "-"}` },
    { label: "系统 CPU", value: percent(systemData.systemCpuLoad), subtext: `异常 ${errorData.totalCount ?? errorData.totalErrors ?? "-"}` }
  ]);
  $("#monitorView").textContent = JSON.stringify({
    cache: cacheData,
    devices: deviceData,
    performance: performanceData,
    system: systemData,
    errors: errorData
  }, null, 2);

  const failureCount = results.filter((item) => item.status === "rejected").length;
  if (failureCount === results.length) {
    setMonitorState("监控数据全部加载失败", "error");
    throw new Error("监控数据加载失败");
  }
  if (failureCount) {
    setMonitorState(`部分监控指标加载失败（${failureCount}/${results.length}）`, "warning");
    return;
  }
  setMonitorState("");
}

function renderCards(selector, items) {
  $(selector).innerHTML = items.map((item) => {
    const card = Array.isArray(item)
      ? { label: item[0], value: item[1] }
      : (item || { label: "-", value: "-" });
    const meta = Array.isArray(card.meta) && card.meta.length
      ? `<div class="card-meta">${card.meta.map(([label, value]) => `<span>${escapeHtml(String(label))}<b>${escapeHtml(String(value ?? "-"))}</b></span>`).join("")}</div>`
      : "";
    const subtext = card.subtext ? `<div class="card-subtext">${escapeHtml(String(card.subtext))}</div>` : "";
    const icon = card.icon
      ? `<span class="card-icon"><img src="./icons/${escapeAttr(card.icon)}" alt=""></span>`
      : "";
    const ring = card.ringValue !== undefined && card.ringValue !== null
      ? `<span class="cache-ring" style="--ring-value:${Math.max(0, Math.min(100, numberValue(card.ringValue, 0) * 100))}%"></span>`
      : "";
    return `
      <div class="card ${card.tone ? `tone-${escapeAttr(card.tone)}` : ""}">
        <small>${escapeHtml(card.label ?? "-")}</small>
        ${icon}${ring}
        <strong>${escapeHtml(String(card.value ?? "-"))}</strong>
        ${meta}
        ${subtext}
      </div>
    `;
  }).join("");
}

function downloadJson(fileName, data) {
  const blob = new Blob([JSON.stringify(data, null, 2)], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = fileName;
  link.click();
  URL.revokeObjectURL(url);
}

function toast(message, isError = false) {
  const target = $("#toast");
  target.textContent = message;
  target.style.background = isError ? "#9e3f35" : "#101410";
  target.classList.add("show");
  setTimeout(() => target.classList.remove("show"), 2600);
}

function percent(value) {
  if (typeof value !== "number" || Number.isNaN(value) || value < 0) {
    return "-";
  }
  const normalized = value <= 1 ? value * 100 : value;
  return `${normalized.toFixed(1)}%`;
}

function bytes(value) {
  if (typeof value !== "number") {
    return "-";
  }
  if (value > 1024 * 1024 * 1024) {
    return `${(value / 1024 / 1024 / 1024).toFixed(2)} GB`;
  }
  if (value > 1024 * 1024) {
    return `${(value / 1024 / 1024).toFixed(1)} MB`;
  }
  return `${value} B`;
}

function formatTs(value) {
  if (!value) {
    return "-";
  }
  return new Date(value).toLocaleString();
}

function formatValue(value) {
  if (value === null || value === undefined) {
    return "-";
  }
  if (typeof value === "object") {
    return JSON.stringify(value);
  }
  return String(value);
}

function getDeviceById(deviceId) {
  return state.devices.find((item) => (item.id || item.deviceId) === deviceId) || null;
}

function deviceProtocolCode(device) {
  return canonicalProtocolForUi(device?.protocolType || device?.connectionType || "");
}

function syncControlCommandExample() {
  const deviceId = $("#controlDeviceSelect")?.value || selectedDeviceId();
  const device = getDeviceById(deviceId);
  const protocol = deviceProtocolCode(device);
  const preset = controlCommandPresets[protocol] || controlCommandPresets.DEFAULT;
  const defaultExampleText = JSON.stringify(controlCommandPresets.DEFAULT.payload, null, 2);
  const exampleText = JSON.stringify(preset.payload, null, 2);
  const exampleNode = $("#commandExample");
  const helpNode = $("#commandHelpText");
  const input = $("#commandInput");

  if (exampleNode) {
    exampleNode.textContent = exampleText;
  }
  if (helpNode) {
    helpNode.textContent = preset.helpText;
  }
  if (input && (!input.value.trim() || input.value === state.lastSuggestedCommandText || input.value === defaultExampleText)) {
    input.value = exampleText;
  }
  state.lastSuggestedCommandText = exampleText;
}

function canonicalProtocolForUi(protocolCode) {
  const normalized = String(protocolCode || "").trim().toUpperCase().replace(/-/g, "_");
  if (normalized === "OPC_UA_PLC4X" || normalized === "OPCUA_PLC4X") {
    return "OPC_UA";
  }
  return normalized;
}

function syncProtocolSelectionToDevice(loadDiff = true) {
  const deviceId = $("#connectionDeviceSelect")?.value || selectedDeviceId();
  if (!deviceId) {
    setProtocolFormState("选择设备后可编辑连接参数", "muted");
    if (loadDiff) {
      setProtocolDiffState("选择设备后查看连接配置差异", "muted");
    }
    return;
  }
  const device = getDeviceById(deviceId);
  const protocol = deviceProtocolCode(device);
  if (!protocol) {
    setProtocolFormState("当前设备没有可识别的协议类型", "warning");
    if (loadDiff) {
      setProtocolDiffState("当前设备缺少协议信息，无法生成配置差异", "warning");
    }
    return;
  }
  if ($("#protocolSelect").value !== protocol) {
    $("#protocolSelect").value = protocol;
    renderSelectedProtocol();
  } else if (!state.currentProtocol || state.currentProtocol.protocol !== protocol) {
    renderSelectedProtocol();
  }
  if (loadDiff) {
    loadDeviceDiff().catch((error) => toast(error.message, true));
  }
}

function buildRuntimeStatusMap(deviceMonitor, runningDevices) {
  const map = {};
  const connections = Array.isArray(deviceMonitor?.connections) ? deviceMonitor.connections : [];
  connections.forEach((connection) => {
    if (!connection?.deviceId) {
      return;
    }
    map[connection.deviceId] = {
      ...(map[connection.deviceId] || {}),
      connected: connection.connected === true,
      isRunning: connection.connected === true || connection.expectedOnly === true || connection.status === "CONNECTING",
      status: connection.status || null,
      snapshot: connection
    };
  });
  runningDevices.forEach((deviceId) => {
    map[deviceId] = {
      ...(map[deviceId] || {}),
      isRunning: true
    };
  });
  return map;
}

function buildUnifiedRuntimeStatusMap(runtimeSnapshots) {
  const map = {};
  runtimeSnapshots.forEach((snapshot) => {
    if (!snapshot?.deviceId) {
      return;
    }
    map[snapshot.deviceId] = {
      connected: snapshot.connected === true,
      isRunning: snapshot.running === true || snapshot.starting === true,
      reconnecting: snapshot.reconnecting === true,
      status: snapshot.phase || null,
      snapshot
    };
  });
  return map;
}

function getRuntimeStatus(deviceId) {
  return state.runtimeStatus[deviceId] || null;
}

function resolveDeviceStatus(device, runtime) {
  if (["FAILED", "DEGRADED", "RECONNECTING"].includes(runtime?.status)) {
    return runtime.status;
  }
  if (runtime?.connected) {
    return "ONLINE";
  }
  if (runtime?.isRunning) {
    return "RUNNING";
  }
  return device?.status || "OFFLINE";
}

function renderDeviceStatus(status, runtime, device) {
  const cssClass = status === "ONLINE"
    ? "status-good"
    : ["RUNNING", "RECONNECTING", "DEGRADED"].includes(status)
      ? "status-warn"
      : "status-bad";
  const configStatus = localizeDeviceStatus(device?.status || "UNKNOWN");
  const detail = runtime?.snapshot?.degradedReason
    ? runtime.snapshot.degradedReason
    : runtime?.connected
      ? "运行连接已建立"
    : runtime?.isRunning
      ? "已启动，等待连接建立"
      : `配置状态 ${configStatus}`;
  return `<div class="${cssClass}">${escapeHtml(localizeDeviceStatus(status))}</div><small class="status-detail">${escapeHtml(detail)}</small>`;
}

function fieldHelpText(field) {
  if (!field) {
    return "";
  }
  if (field.description) {
    return field.description;
  }
  if (field.required) {
    return "";
  }
  if (field.defaultValue !== null && field.defaultValue !== undefined && String(field.defaultValue) !== "") {
    return `可选，默认值：${field.defaultValue}`;
  }
  if (field.group === "advanced") {
    return "可选高级覆盖项。留空时使用自动生成值或后端默认值。";
  }
  return "可选。支持时留空使用后端默认值。";
}

function renderGroupDescription(protocol, group) {
  if (group !== "advanced") {
    return "";
  }
  const protocolCode = protocol?.protocol || "";
  const description = protocolCode.startsWith("MODBUS")
    ? "PLC4X 高级覆盖项。主机、端口和串口设置仍是标准配置来源。"
    : "可选高级覆盖项和兼容别名。没有明确调优需求时请留空。";
  return `<p class="group-description">${escapeHtml(description)}</p>`;
}

function cssEscape(value) {
  if (window.CSS && typeof window.CSS.escape === "function") {
    return window.CSS.escape(String(value ?? ""));
  }
  return String(value ?? "").replace(/["\\]/g, "\\$&");
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function escapeAttr(value) {
  return escapeHtml(value).replaceAll("`", "&#096;");
}

const tableActions = {
  startDevice,
  stopDevice,
  showDeviceStatus,
  showDiff,
  editLocalDevice,
  deleteLocalDevice
};

window.startDevice = (deviceId) => tableActions.startDevice(deviceId).catch((error) => toast(error.message, true));
window.stopDevice = (deviceId) => tableActions.stopDevice(deviceId).catch((error) => toast(error.message, true));
window.showDeviceStatus = (deviceId) => tableActions.showDeviceStatus(deviceId).catch((error) => toast(error.message, true));
window.showDiff = (deviceId) => tableActions.showDiff(deviceId).catch((error) => toast(error.message, true));
window.editLocalDevice = (deviceId) => tableActions.editLocalDevice(deviceId).catch((error) => toast(error.message, true));
window.deleteLocalDevice = (deviceId) => tableActions.deleteLocalDevice(deviceId).catch((error) => toast(error.message, true));
window.selectDevice = (deviceId) => selectDevice(deviceId);


