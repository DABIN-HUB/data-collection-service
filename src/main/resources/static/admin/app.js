const state = {
  token: localStorage.getItem("collectorToken") || "ops-token",
  devices: [],
  protocols: [],
  runtimeStatus: {},
  currentProtocol: null,
  currentLocalProtocol: null,
  localDeviceEditingId: null,
  realtimeTimer: null,
  lastSuggestedCommandText: "",
  realtimeSearch: "",
  realtimePoints: [],
  selectedRealtimePointKey: null,
  activeWorkbenchTab: "points"
};

const $ = (selector) => document.querySelector(selector);
const API_BASE = resolveContextPath();
const HIDDEN_PROTOCOLS = new Set(["OPC_UA_PLC4X"]);

const adaptiveDefaults = {
  baseCollectionInterval: 2000,
  minCollectionInterval: 100,
  maxCollectionInterval: 3600000,
  pointChangeThreshold: 1
};

const designLab = window.__collectorDesignLab || null;
const previewMode = Boolean(designLab && typeof designLab.isPreviewMode === "function" && designLab.isPreviewMode());
const previewData = previewMode && typeof designLab.previewDataset === "function"
  ? designLab.previewDataset()
  : null;

const controlCommandPresets = {
  DEFAULT: {
    helpText: "Default example. Replace command and params with the collector-specific operation you need.",
    payload: { command: "status", params: {} }
  },
  SIEMENS_S7: {
    helpText: "S7 supports shorthand addresses like DB1.DBW0 and native PLC4X addresses like %DB1.DBX0.0:BOOL. MODE/SYS/USR/ALM are subscription modes, not normal point addresses.",
    payload: { command: "diagnostic", params: {} }
  }
};

document.addEventListener("DOMContentLoaded", () => {
  $("#tokenInput").value = state.token;
  bindEvents();
  bindConsoleShell();
  startLiveClock();
  if (previewMode) {
    hydratePreviewMode();
    return;
  }
  refreshAll();
});

function bindEvents() {
  $("#saveTokenBtn").addEventListener("click", () => {
    state.token = $("#tokenInput").value.trim();
    localStorage.setItem("collectorToken", state.token);
    toast("令牌已保存");
  });
  $("#refreshAllBtn").addEventListener("click", refreshAll);
  $("#reloadDevicesBtn").addEventListener("click", reloadDevices);
  $("#openLocalDeviceBtn").addEventListener("click", () => openLocalDeviceForm());
  $("#cancelLocalDeviceBtn").addEventListener("click", closeLocalDeviceForm);
  $("#saveLocalDeviceBtn").addEventListener("click", saveLocalDevice);
  $("#formatLocalPointsBtn").addEventListener("click", formatLocalPointsJson);
  $("#exportConfigBtn").addEventListener("click", exportConfig);
  $("#syncConfigBtn").addEventListener("click", syncConfig);
  $("#protocolSelect").addEventListener("change", renderSelectedProtocol);
  $("#localProtocolSelect").addEventListener("change", renderLocalProtocolSelection);
  $("#connectionDeviceSelect").addEventListener("change", syncProtocolSelectionToDevice);
  $("#loadConnectionBtn").addEventListener("click", loadConnection);
  $("#saveConnectionBtn").addEventListener("click", saveConnection);
  $("#toggleRealtimeBtn").addEventListener("click", toggleRealtime);
  $("#resetAdaptiveBtn").addEventListener("click", resetAdaptive);
  $("#writePointsBtn").addEventListener("click", writePoints);
  $("#controlDeviceSelect").addEventListener("change", syncControlCommandExample);
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

  const realtimeSelect = $("#realtimeDeviceSelect");
  if (realtimeSelect) {
    realtimeSelect.addEventListener("change", () => {
      syncSelectedDeviceSummary();
      renderDevices();
      loadRealtime().catch((error) => toast(error.message, true));
    });
  }

  const pointSearch = $("#devicePointSearch");
  if (pointSearch) {
    pointSearch.addEventListener("input", (event) => {
      state.realtimeSearch = String(event.target.value || "").trim().toLowerCase();
      loadRealtime().catch((error) => toast(error.message, true));
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

function selectedDeviceId() {
  return $("#realtimeDeviceSelect")?.value || $("#connectionDeviceSelect")?.value || "";
}

function selectDevice(deviceId) {
  if (!deviceId) {
    return;
  }
  ["#connectionDeviceSelect", "#realtimeDeviceSelect", "#controlDeviceSelect", "#shadowDeviceSelect"].forEach((selector) => {
    const select = $(selector);
    if (!select) {
      return;
    }
    const option = Array.from(select.options).find((item) => item.value === deviceId);
    if (option) {
      select.value = deviceId;
    }
  });
  syncProtocolSelectionToDevice(false);
  syncControlCommandExample();
  syncSelectedDeviceSummary(deviceId);
  renderDevices();
  activateWorkbenchTab("points");
  loadRealtime().catch((error) => toast(error.message, true));
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

  const qualityText = point.quality || (point.qualityAcceptable === false ? "BAD" : "GOOD");
  const address = point.address || point.registerAddress || point.pointAddress || "-";
  const scale = point.scalingFactor ?? point.scale ?? point.factor ?? "-";
  const pointCode = point.pointCode || point.pointId || "-";
  const unit = point.unit || point.sourceUnit || "-";
  const processText = `${point.processingTime ?? "-"} ms`;

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
    badge.className = `badge ${point.qualityAcceptable === false ? "badge-alert" : "badge-remote"}`;
  }

  setInspectorField("#inspectorPointCode", pointCode);
  setInspectorField("#inspectorPointType", point.dataType || point.driverDataType || point.type || "-");
  setInspectorField("#inspectorPointAddress", formatValue(address));
  setInspectorField("#inspectorPointAccess", point.readWrite || point.accessMode || "R");
  setInspectorField("#inspectorPointScale", formatValue(scale));
  setInspectorField("#inspectorPointValue", formatValue(point.value));
  setInspectorField("#inspectorPointRawValue", formatValue(point.rawValue));
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

function localizeDeviceStatus(status) {
  switch (String(status || "").toUpperCase()) {
    case "ONLINE":
      return "鍦ㄧ嚎";
    case "RUNNING":
      return "启动中";
    case "OFFLINE":
      return "离线";
    default:
      return status || "未知";
  }
}

function syncSelectedDeviceSummary(deviceId = $("#realtimeDeviceSelect")?.value) {
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

async function refreshAll() {
  if (previewMode) {
    hydratePreviewMode();
    return;
  }
  try {
    await Promise.all([
      loadProtocols(),
      loadDevices(),
      loadOverview(),
      loadMonitor()
    ]);
    $("#lastRefresh").textContent = new Date().toLocaleString();
  } catch (error) {
    toast(error.message, true);
  }
}

async function callApi(path, options = {}) {
  if (previewMode) {
    return previewApi(path, options);
  }
  const headers = new Headers(options.headers || {});
  if (!headers.has("Content-Type") && options.body) {
    headers.set("Content-Type", "application/json");
  }
  if (state.token) {
    headers.set("X-Collector-Token", state.token);
  }
  const response = await fetch(`${API_BASE}${path}`, { ...options, headers });
  const text = await response.text();
  const body = text ? JSON.parse(text) : {};
  if (!response.ok) {
    throw apiError(body.message || `HTTP ${response.status}`, body, response.status);
  }
  if (body.status === "error") {
    throw apiError(body.message || "请求失败", body, response.status);
  }
  if (typeof body.code === "number" && body.code !== 200) {
    throw apiError(body.message || `涓氬姟閿欒 ${body.code}`, body, response.status);
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

function previewApi(path, options = {}) {
  const normalizedPath = String(path || "");
  const method = String(options.method || "GET").toUpperCase();
  const devices = previewData?.devices || [];
  const deviceMap = new Map(devices.map((item) => [item.id || item.deviceId, item]));
  const pointsMap = previewData?.pointConfigs || {};
  const runtimeMap = previewData?.runtimeValues || {};

  if (normalizedPath === "/api/protocols") {
    return Promise.resolve({
      status: "success",
      data: [
        {
          protocol: "MODBUS_TCP",
          title: "Modbus TCP",
          description: "适用于锅炉、电表、泵站等以寄存器为中心的设备采集。",
          implemented: true,
          aliases: ["MODBUS-TCP"],
          pointAddressHints: ["40001", "30001"],
          dataTypes: ["BOOLEAN", "INT", "FLOAT", "DOUBLE", "STRING"],
          driverTypeEnabled: false,
          connectionFields: [
            { name: "host", label: "Host", type: "text", storage: "topLevel", required: true, group: "connection" },
            { name: "port", label: "Port", type: "number", storage: "topLevel", required: true, group: "connection", defaultValue: 502 }
          ]
        },
        {
          protocol: "MODBUS_RTU",
          title: "Modbus RTU",
          description: "适用于串口泵站、仪表和传统 PLC 设备。",
          implemented: true,
          aliases: ["MODBUS-RTU"],
          pointAddressHints: ["30001", "40001"],
          dataTypes: ["BOOLEAN", "INT", "FLOAT", "DOUBLE"],
          driverTypeEnabled: false,
          connectionFields: [
            { name: "host", label: "COM", type: "text", storage: "topLevel", required: true, group: "connection", defaultValue: "COM3" },
            { name: "port", label: "Baud", type: "number", storage: "topLevel", required: true, group: "connection", defaultValue: 9600 }
          ]
        },
        {
          protocol: "OPC_UA",
          title: "OPC UA",
          description: "适用于工艺站、混配站和产线 PLC 的结构化节点采集。",
          implemented: true,
          aliases: ["OPCUA"],
          pointAddressHints: ["ns=2;s=Tank.Level"],
          dataTypes: ["BOOLEAN", "INT", "FLOAT", "DOUBLE", "STRING"],
          driverTypeEnabled: false,
          connectionFields: [
            { name: "host", label: "Endpoint", type: "text", storage: "topLevel", required: true, group: "connection" },
            { name: "port", label: "Port", type: "number", storage: "topLevel", required: true, group: "connection", defaultValue: 4840 }
          ]
        },
        {
          protocol: "MQTT",
          title: "MQTT",
          description: "适用于网关、边缘盒子和主题订阅采集场景。",
          implemented: true,
          aliases: ["MQTT"],
          pointAddressHints: ["topic/path"],
          dataTypes: ["BOOLEAN", "INT", "FLOAT", "DOUBLE", "STRING"],
          driverTypeEnabled: false,
          connectionFields: [
            { name: "host", label: "Broker", type: "text", storage: "topLevel", required: true, group: "connection" },
            { name: "port", label: "Port", type: "number", storage: "topLevel", required: true, group: "connection", defaultValue: 1883 }
          ]
        }
      ]
    });
  }

  if (normalizedPath === "/api/config/summary") {
    return Promise.resolve({
      status: "success",
      data: {
        deviceCount: devices.length,
        pointCount: Object.values(pointsMap).reduce((sum, items) => sum + items.length, 0),
        connectionCount: devices.length,
        listenerCount: 3,
        nextSyncTime: "2026-07-02T13:12:43+08:00",
        cacheStats: {
          deviceCount: devices.length,
          pointCount: Object.values(pointsMap).reduce((sum, items) => sum + items.length, 0),
          connectionCount: devices.length
        }
      }
    });
  }

  if (normalizedPath === "/api/device/running") {
    return Promise.resolve({
      status: "success",
      data: devices.filter((item) => ["ONLINE", "RUNNING"].includes(item.status)).map((item) => item.id || item.deviceId)
    });
  }

  if (normalizedPath === "/api/config/devices") {
    return Promise.resolve({ status: "success", data: { devices } });
  }

  if (normalizedPath === "/monitor/devices") {
    return Promise.resolve({
      status: "success",
      data: {
        activeConnections: 3,
        missingConnections: ["water-pump-02"],
        connections: devices.map((device) => ({
          deviceId: device.id || device.deviceId,
          connected: device.status === "ONLINE",
          expectedOnly: device.status === "RUNNING",
          status: device.status
        }))
      }
    });
  }

  if (normalizedPath === "/monitor/cache") {
    return Promise.resolve({
      status: "success",
      data: {
        totalHitRate: 0.932,
        totalAccess: 18241,
        level1HitRate: 0.971
      }
    });
  }

  if (normalizedPath === "/monitor/performance") {
    return Promise.resolve({
      status: "success",
      data: {
        avgLatencyMs: 12.4,
        batchTimeoutRate: 0.001
      }
    });
  }

  if (normalizedPath === "/monitor/system") {
    return Promise.resolve({
      status: "success",
      data: {
        heapUsed: 512 * 1024 * 1024,
        threadCount: 68,
        systemCpuLoad: 0.31
      }
    });
  }

  if (normalizedPath === "/monitor/errors") {
    return Promise.resolve({
      status: "success",
      data: {
        totalCount: 7,
        totalErrors: 7
      }
    });
  }

  if (normalizedPath === "/health") {
    return Promise.resolve({
      status: "success",
      data: {
        status: "DOWN",
        overallStatus: "DOWN"
      }
    });
  }

  const deviceDataMatch = normalizedPath.match(/^\/api\/data\/device\/([^/]+)$/);
  if (deviceDataMatch) {
    const deviceId = decodeURIComponent(deviceDataMatch[1]);
    return Promise.resolve({
      status: "success",
      data: runtimeMap[deviceId] || {}
    });
  }

  const pointConfigMatch = normalizedPath.match(/^\/api\/config\/device\/([^/]+)\/points$/);
  if (pointConfigMatch) {
    const deviceId = decodeURIComponent(pointConfigMatch[1]);
    if (method === "PUT" && options.body) {
      try {
        const parsed = JSON.parse(options.body);
        if (Array.isArray(parsed)) {
          previewData.pointConfigs[deviceId] = parsed;
        }
      } catch (error) {
        // ignore preview save parse errors
      }
      return Promise.resolve({ status: "success", data: { updated: true } });
    }
    return Promise.resolve({
      status: "success",
      data: {
        points: (pointsMap[deviceId] || []).map((item) => JSON.parse(JSON.stringify(item)))
      }
    });
  }

  const connectionMatch = normalizedPath.match(/^\/api\/config\/device\/([^/]+)\/connection$/);
  if (connectionMatch) {
    const deviceId = decodeURIComponent(connectionMatch[1]);
    const device = deviceMap.get(deviceId);
    return Promise.resolve({
      status: "success",
      data: {
        connection: {
          deviceId,
          connectionType: device?.protocolType || "",
          host: device?.ipAddress || device?.host || "",
          port: device?.port || "",
          extJson: {}
        }
      }
    });
  }

  const diffMatch = normalizedPath.match(/^\/api\/config\/device\/([^/]+)\/diff$/);
  if (diffMatch) {
    const deviceId = decodeURIComponent(diffMatch[1]);
    return Promise.resolve({
      status: "success",
      data: {
        deviceId,
        status: "preview",
        localChanged: ["collectionInterval", "points[2].alarmRule"],
        remoteChanged: []
      }
    });
  }

  const shadowMatch = normalizedPath.match(/^\/api\/shadow\/([^/]+)(\/desired)?$/);
  if (shadowMatch) {
    const deviceId = decodeURIComponent(shadowMatch[1]);
    const desired = Boolean(shadowMatch[2]);
    return Promise.resolve({
      status: "success",
      data: desired
        ? { deviceId, desired: { targetMode: "AUTO", targetLoad: 0.76 } }
        : {
            deviceId,
            reported: {
              status: deviceMap.get(deviceId)?.status || "UNKNOWN",
              updatedAt: "2026-07-02 13:12:43",
              points: Object.keys(runtimeMap[deviceId] || {}).slice(0, 4)
            }
          }
    });
  }

  if (
    normalizedPath === "/api/config/export"
    || normalizedPath === "/api/config/sync"
    || normalizedPath === "/api/device/reload"
    || /\/api\/device\/.+\/(start|start-local|stop)$/.test(normalizedPath)
    || /\/api\/data\/device\/.+\/reset-adaptive$/.test(normalizedPath)
    || /\/api\/control\/device\/.+\/(points|command)$/.test(normalizedPath)
  ) {
    return Promise.resolve({ status: "success", data: { ok: true, preview: true } });
  }

  if (/^\/api\/device\/.+\/status$/.test(normalizedPath)) {
    const deviceId = decodeURIComponent(normalizedPath.split("/")[3] || "");
    return Promise.resolve({
      status: "success",
      data: {
        deviceId,
        status: deviceMap.get(deviceId)?.status || "OFFLINE",
        lastHeartbeat: "2026-07-02 13:12:43",
        queueDepth: 2
      }
    });
  }

  return Promise.resolve({ status: "success", data: {} });
}

function hydratePreviewMode() {
  if (!previewData) {
    return;
  }
  loadProtocols()
    .then(() => Promise.all([loadDevices(), loadOverview(), loadMonitor()]))
    .then(() => {
      activateWorkbenchTab("points");
      activateConsoleTab("note-log");
      const defaultDevice = previewData.devices[0]?.id || previewData.devices[0]?.deviceId || "";
      if (defaultDevice) {
        selectDevice(defaultDevice);
      }
      toast("预览模式已加载 5 套可切换样式", false);
    })
    .catch((error) => toast(error.message, true));
}

async function loadOverview() {
  const [summaryBody, runningBody, health, cacheBody] = await Promise.allSettled([
    callApi("/api/config/summary"),
    callApi("/api/device/running"),
    callApi("/health"),
    callApi("/monitor/cache")
  ]);

  const summary = summaryBody.status === "fulfilled" ? dataOf(summaryBody.value) : {};
  const running = runningBody.status === "fulfilled" ? dataOf(runningBody.value) : [];
  const healthData = health.status === "fulfilled" ? dataOf(health.value) : {};
  const cache = cacheBody.status === "fulfilled" ? dataOf(cacheBody.value) : {};
  const stats = summary.cacheStats || {};
  const totalDevices = Number(stats.deviceCount ?? summary.deviceCount ?? (Array.isArray(state.devices) ? state.devices.length : 0) ?? 0);
  const onlineDevices = Array.isArray(running) ? running.length : 0;
  const offlineDevices = totalDevices > 0 ? Math.max(totalDevices - onlineDevices, 0) : "-";
  const pointCount = Number(stats.pointCount ?? summary.pointCount ?? 0);
  const connectionCount = Number(stats.connectionCount ?? summary.connectionCount ?? 0);
  const successRate = percent(cache.totalHitRate);
  const healthStatus = healthData.status || healthData.overallStatus || "UNKNOWN";
  const latestSync = formatTs(summary.nextSyncTime);

  renderCards("#overviewCards", [
    {
      label: "采集器总数",
      value: totalDevices || "-",
      meta: [["在线", onlineDevices || 0], ["离线", offlineDevices]],
      tone: "blue"
    },
    {
      label: "点位总数",
      value: pointCount || "-",
      meta: [["杩炴帴", connectionCount || 0], ["鍋ュ悍", healthStatus]],
      tone: "green"
    },
    {
      label: "实时数据点",
      value: pointCount || "-",
      subtext: latestSync === "-" ? "最近上报等待中" : `计划同步 ${latestSync}`,
      tone: "teal"
    },
    {
      label: "运行设备",
      value: onlineDevices || "-",
      meta: [["同步监听", summary.listenerCount ?? "-"], ["状态", healthStatus]],
      tone: "orange"
    },
    {
      label: "缓存命中率",
      value: successRate,
      subtext: `总访问 ${cache.totalAccess ?? "-"}`,
      tone: "purple"
    },
    {
      label: "配置健康度",
      value: healthStatus,
      subtext: latestSync === "-" ? "暂无同步时间" : `下次同步 ${latestSync}`,
      tone: "green"
    }
  ]);
}

async function loadDevices() {
  const [devicesBody, monitorBody, runningBody] = await Promise.allSettled([
    callApi("/api/config/devices"),
    callApi("/monitor/devices"),
    callApi("/api/device/running")
  ]);
  const payload = devicesBody.status === "fulfilled" ? dataOf(devicesBody.value) : {};
  const monitor = monitorBody.status === "fulfilled" ? dataOf(monitorBody.value) : {};
  const running = runningBody.status === "fulfilled" ? dataOf(runningBody.value) : [];
  state.devices = payload.devices || [];
  state.runtimeStatus = buildRuntimeStatusMap(monitor, Array.isArray(running) ? running : []);
  renderDevices();
  fillDeviceSelects();
}

function renderDevices() {
  const currentDeviceId = selectedDeviceId();
  const rows = state.devices.map((device) => {
    const id = device.id || device.deviceId;
    const address = [device.ipAddress, device.port].filter(Boolean).join(":") || "-";
    const local = isLocalDevice(device);
    const runtime = getRuntimeStatus(id);
    const status = resolveDeviceStatus(device, runtime);
    const statusLabel = localizeDeviceStatus(status);
    const sourceLabel = local ? "本地临时" : "远端同步";
    const selected = currentDeviceId === id;
    const editButtons = local
      ? `<button onclick="editLocalDevice('${escapeAttr(id)}')">编辑</button>
         <button onclick="deleteLocalDevice('${escapeAttr(id)}')" class="danger">删除</button>`
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
                  <div class="device-card-subtitle">${escapeHtml(id)} 路 ${escapeHtml(sourceLabel)}</div>
                </div>
                <span class="badge ${local ? "badge-local" : "badge-remote"}">${escapeHtml(statusLabel)}</span>
              </div>
              <div class="device-card-meta">
                <span>鍗忚 ${escapeHtml(device.protocolType || device.connectionType || "-")}</span>
                <span>鍦板潃 ${escapeHtml(address)}</span>
                <span>周期 ${device.collectionInterval ?? "-"} ms</span>
              </div>
            </button>
            <div class="inline-actions device-card-actions">
              <button onclick="startDevice('${escapeAttr(id)}')">启动</button>
              <button onclick="stopDevice('${escapeAttr(id)}')" class="danger">鍋滄</button>
              <button onclick="showDeviceStatus('${escapeAttr(id)}')">鐘舵€?/button>
              <button onclick="showDiff('${escapeAttr(id)}')">Diff</button>
              ${editButtons}
            </div>
          </div>
        </td>
      </tr>`;
  }).join("");
  $("#deviceRows").innerHTML = rows || `<tr><td>暂无设备配置</td></tr>`;
}

function isLocalDevice(device) {
  return device && (device.configSource === "local" || device.temporaryConfig === true);
}

function fillDeviceSelects() {
  const options = state.devices.map((device) => {
    const id = device.id || device.deviceId;
    const source = isLocalDevice(device) ? "local" : "sync";
    return `<option value="${escapeAttr(id)}">${escapeHtml(device.deviceName || id)} (${escapeHtml(id)} / ${source})</option>`;
  }).join("");
  ["#connectionDeviceSelect", "#realtimeDeviceSelect", "#controlDeviceSelect", "#shadowDeviceSelect"].forEach((selector) => {
    const select = $(selector);
    const previous = select.value;
    select.innerHTML = options;
    if (previous) {
      select.value = previous;
    }
    if (!select.value && select.options.length) {
      select.selectedIndex = 0;
    }
  });
  syncProtocolSelectionToDevice(false);
  syncControlCommandExample();
  syncSelectedDeviceSummary();
  renderDevices();
  if (state.devices.length) {
    loadRealtime().catch((error) => toast(error.message, true));
  } else {
    clearSelectedPointInspector();
  }
}

function getProtocolSchema(protocolCode) {
  const canonical = canonicalProtocolForUi(protocolCode);
  return state.protocols.find((item) => item.protocol === canonical) || null;
}

function groupTitle(group) {
  switch (group) {
    case "connection":
      return "Connection";
    case "protocol":
      return "Protocol";
    case "security":
      return "Security";
    case "advanced":
      return "Advanced";
    case "topic":
      return "Topics";
    case "request":
      return "Request";
    case "bridge":
      return "Bridge";
    default:
      return "Fields";
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
  const status = protocol.implemented ? "Implemented" : "Placeholder";
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
        const label = field.label || field.name || "-";
        const description = field.description || "协议扩展字段";
        const storage = field.storage ? `；保存位置：${field.storage}` : "";
        return `<li><code>${escapeHtml(field.name || "-")}</code> / ${escapeHtml(label)}：${escapeHtml(description + storage)}</li>`;
      }).join("")}</ul>
    `
    : '<p><code>pointFields</code>：无。含义：当前协议没有额外的点位扩展字段。</p>';
  const riskNoteHtml = renderProtocolRiskNote(protocol);
  return `
    <strong>${escapeHtml(protocol.title)}</strong>
    <span class="${protocol.implemented ? "status-good" : "status-bad"}">${status}</span>
    <p>${escapeHtml(protocol.description || "")}</p>
    ${riskNoteHtml}
    <p>Aliases: ${aliases}</p>
    <p>Address hints: ${addressHints}</p>
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

function renderField(field, formId) {
  const required = field.required ? `<span class="field-required">*</span>` : "";
  const hint = field.requiredWhen ? `<span class="field-hint">${escapeHtml(field.requiredWhen)}</span>` : "";
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
      ${escapeHtml(field.label || field.name)} ${required} ${hint}
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
      errors.push(`${field.label || field.name} is required`);
      if (label) {
        setFieldError(label, "Required");
      }
      return;
    }
    let parsed;
    try {
      parsed = parseValue(rawValue, field.type);
    } catch (error) {
      errors.push(`${field.label || field.name}: ${error.message}`);
      if (label) {
        setFieldError(label, "Invalid format");
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

function openLocalDeviceForm(bundle = null) {
  state.localDeviceEditingId = bundle?.device?.id || bundle?.device?.deviceId || null;
  $("#localDevicePanel").classList.remove("hidden");
  $("#localDevicePanel").scrollIntoView({ behavior: "smooth", block: "start" });

  const device = bundle?.device || {};
  const connection = bundle?.connection || {};
  const points = bundle?.points || [defaultPointTemplate(device.id || device.deviceId || "local-device")];
  const deviceId = device.id || device.deviceId || "";
  const protocol = canonicalProtocolForUi(
    device.protocolType || connection.connectionType || $("#localProtocolSelect").value || "MODBUS_TCP"
  );
  const adaptive = resolveAdaptiveDefaults(device, points);

  $("#localEditorTitle").textContent = state.localDeviceEditingId ? "Edit local temporary device" : "Create local temporary device";
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
    pointName: "娓╁害",
    deviceId,
    address: "40001",
    dataType: "FLOAT",
    readWrite: "R",
    status: 1,
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
    toast(`JSON format error: ${error.message}`, true);
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
  toast("Local temporary device saved");
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
  openLocalDeviceForm(payload.bundle);
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
}

function renderSelectedProtocol() {
  const protocolCode = canonicalProtocolForUi($("#protocolSelect").value);
  $("#protocolSelect").value = protocolCode;
  state.currentProtocol = getProtocolSchema(protocolCode);
  const protocol = state.currentProtocol;
  updateProtocolMetaHelp("#protocolMetaHelp", protocol, `${protocol?.title || protocolCode || "协议"} 协议说明`);
  if (!protocol) {
    $("#connectionForm").innerHTML = "";
    return;
  }
  renderProtocolForm("#connectionForm", protocol, "connectionForm");
}

async function loadConnection() {
  const deviceId = $("#connectionDeviceSelect").value;
  if (!deviceId) {
    toast("Select a device first", true);
    return;
  }
  syncProtocolSelectionToDevice(false);
  const body = await callApi(`/api/config/device/${encodeURIComponent(deviceId)}/connection`);
  const connection = dataOf(body).connection || {};
  fillProtocolForm("#connectionForm", state.currentProtocol, connection);
  await loadDeviceDiff();
  toast("Connection config loaded");
}

async function saveConnection() {
  const deviceId = $("#connectionDeviceSelect").value;
  const device = getDeviceById(deviceId);
  const protocol = deviceProtocolCode(device);
  if (!deviceId || !protocol) {
    toast("Select both device and protocol", true);
    return;
  }
  if ($("#protocolSelect").value !== protocol) {
    $("#protocolSelect").value = protocol;
    renderSelectedProtocol();
  }
  const payload = collectProtocolForm("#connectionForm", state.currentProtocol, deviceId);
  payload.connectionType = protocol;
  await callApi(`/api/config/device/${encodeURIComponent(deviceId)}/connection`, {
    method: "PUT",
    body: JSON.stringify(payload)
  });
  toast("Connection config saved");
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
    return;
  }
  syncProtocolSelectionToDevice(false);
  const body = await callApi(`/api/config/device/${encodeURIComponent(deviceId)}/diff`);
  $("#diffView").textContent = JSON.stringify(dataOf(body), null, 2);
}

async function startDevice(deviceId) {
  const device = state.devices.find((item) => (item.id || item.deviceId) === deviceId);
  const action = isLocalDevice(device) ? "start-local" : "start";
  await callApi(`/api/device/${encodeURIComponent(deviceId)}/${action}`, { method: "POST" });
  await Promise.all([loadDevices(), loadOverview(), loadMonitor()]);
  toast(`宸茶姹傚惎鍔?${deviceId}`);
}

async function stopDevice(deviceId) {
  await callApi(`/api/device/${encodeURIComponent(deviceId)}/stop`, { method: "POST" });
  await Promise.all([loadDevices(), loadOverview(), loadMonitor()]);
  toast(`宸茶姹傚仠姝?${deviceId}`);
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
  if (state.realtimeTimer) {
    clearInterval(state.realtimeTimer);
    state.realtimeTimer = null;
    $("#toggleRealtimeBtn").textContent = "鑷姩鍒锋柊";
    return;
  }
  loadRealtime().catch((error) => toast(error.message, true));
  state.realtimeTimer = setInterval(() => {
    loadRealtime().catch((error) => toast(error.message, true));
  }, 3000);
  $("#toggleRealtimeBtn").textContent = "鍋滄鍒锋柊";
}

async function loadRealtime() {
  const deviceId = $("#realtimeDeviceSelect").value;
  if (!deviceId) {
    $("#realtimeRows").innerHTML = `<tr><td colspan="9">暂无实时数据</td></tr>`;
    syncSelectedDeviceSummary();
    clearSelectedPointInspector();
    return;
  }
  syncSelectedDeviceSummary(deviceId);
  const body = await callApi(`/api/data/device/${encodeURIComponent(deviceId)}`);
  const values = body.data || {};
  const points = Object.values(values)
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

  const rows = points.map((point) => {
    const qualityText = point.quality || (point.qualityAcceptable === false ? "BAD" : "GOOD");
    const address = point.address || point.registerAddress || point.pointAddress || "-";
    const scale = point.scalingFactor ?? point.scale ?? point.factor ?? "-";
    return `
      <tr data-point-key="${escapeAttr(point.__pointKey)}" class="${point.__pointKey === state.selectedRealtimePointKey ? "is-selected" : ""}">
        <td>${escapeHtml(point.pointName || point.pointId || "-")}</td>
        <td><code>${escapeHtml(point.pointCode || point.pointId || "-")}</code></td>
        <td>${escapeHtml(point.dataType || point.driverDataType || point.type || "-")}</td>
        <td>${escapeHtml(formatValue(address))}</td>
        <td>${escapeHtml(point.readWrite || point.accessMode || "R")}</td>
        <td>${escapeHtml(formatValue(scale))}</td>
        <td><strong>${escapeHtml(formatValue(point.value))}</strong></td>
        <td class="${point.qualityAcceptable === false ? "status-bad" : "status-good"}">${escapeHtml(qualityText)}</td>
        <td>${point.processingTime ?? "-"} ms</td>
      </tr>`;
  }).join("");
  $("#realtimeRows").innerHTML = rows || `<tr><td colspan="9">暂无匹配的实时数据</td></tr>`;
  if (points.length) {
    renderSelectedPointInspector();
  } else {
    clearSelectedPointInspector();
  }
}

async function resetAdaptive() {
  const deviceId = $("#realtimeDeviceSelect").value;
  if (!deviceId) {
    toast("请选择设备", true);
    return;
  }
  await callApi(`/api/data/device/${encodeURIComponent(deviceId)}/reset-adaptive`, { method: "POST" });
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
    message: error.message || "鎿嶄綔澶辫触"
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
  const deviceId = $("#shadowDeviceSelect").value;
  if (!deviceId) {
    return;
  }
  const body = await callApi(`/api/shadow/${encodeURIComponent(deviceId)}`);
  $("#shadowView").textContent = JSON.stringify(dataOf(body), null, 2);
}

async function saveDesired() {
  const deviceId = $("#shadowDeviceSelect").value;
  if (!deviceId) {
    toast("请选择设备", true);
    return;
  }
  const payload = JSON.parse($("#desiredInput").value);
  const body = await callApi(`/api/shadow/${encodeURIComponent(deviceId)}/desired`, {
    method: "POST",
    body: JSON.stringify(payload)
  });
  $("#shadowView").textContent = JSON.stringify(dataOf(body), null, 2);
  toast("desired 已提交");
}

async function clearDesired() {
  const deviceId = $("#shadowDeviceSelect").value;
  if (!deviceId) {
    toast("请选择设备", true);
    return;
  }
  const body = await callApi(`/api/shadow/${encodeURIComponent(deviceId)}/desired`, { method: "DELETE" });
  $("#shadowView").textContent = JSON.stringify(dataOf(body), null, 2);
  toast("desired 已清理");
}

async function loadMonitor() {
  const [cache, devices, performance, system, errors] = await Promise.allSettled([
    callApi("/monitor/cache"),
    callApi("/monitor/devices"),
    callApi("/monitor/performance"),
    callApi("/monitor/system"),
    callApi("/monitor/errors")
  ]);
  const cacheData = cache.status === "fulfilled" ? dataOf(cache.value) : {};
  const deviceData = devices.status === "fulfilled" ? dataOf(devices.value) : {};
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
    performance: performance.status === "fulfilled" ? dataOf(performance.value) : {},
    system: systemData,
    errors: errorData
  }, null, 2);
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
    return `
      <div class="card ${card.tone ? `tone-${escapeAttr(card.tone)}` : ""}">
        <small>${escapeHtml(card.label ?? "-")}</small>
        <strong>${escapeHtml(String(card.value ?? "-"))}</strong>
        ${meta}
        ${subtext}
      </div>
    `;
  }).join("");
}

function downloadJson(fileName, data) {
  if (previewMode) {
    toast(`预览模式：已模拟导出 ${fileName}`);
    return;
  }
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
  if (typeof value !== "number" || Number.isNaN(value)) {
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
  const device = getDeviceById($("#controlDeviceSelect")?.value);
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
  const deviceId = $("#connectionDeviceSelect").value;
  const device = getDeviceById(deviceId);
  const protocol = deviceProtocolCode(device);
  if (!protocol) {
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

function getRuntimeStatus(deviceId) {
  return state.runtimeStatus[deviceId] || null;
}

function resolveDeviceStatus(device, runtime) {
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
    : status === "RUNNING"
      ? "status-warn"
      : "status-bad";
  const configStatus = device?.status || "-";
  const detail = runtime?.connected
    ? "runtime connected"
    : runtime?.isRunning
      ? "runtime started, waiting for connection"
      : `config ${configStatus}`;
  return `<div class="${cssClass}">${escapeHtml(status)}</div><small class="status-detail">${escapeHtml(detail)}</small>`;
}

function fieldHelpText(field) {
  if (!field) {
    return "";
  }
  if (field.description) {
    return field.description;
  }
  if (field.required) {
    return "Required field";
  }
  if (field.defaultValue !== null && field.defaultValue !== undefined && String(field.defaultValue) !== "") {
    return `Optional. Default: ${field.defaultValue}`;
  }
  if (field.group === "advanced") {
    return "Optional advanced override. Leave empty to use generated or backend defaults.";
  }
  return "Optional. Leave empty to use backend defaults when supported.";
}

function renderGroupDescription(protocol, group) {
  if (group !== "advanced") {
    return "";
  }
  const protocolCode = protocol?.protocol || "";
  const description = protocolCode.startsWith("MODBUS")
    ? "Advanced PLC4X overrides. Host, port and serial settings remain the normal source of truth."
    : "Optional advanced overrides and compatibility aliases. Leave empty unless you need explicit tuning.";
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


