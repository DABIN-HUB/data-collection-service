const state = {
  token: localStorage.getItem("collectorToken") || "",
  devices: [],
  protocols: [],
  runtimeStatus: {},
  currentProtocol: null,
  currentLocalProtocol: null,
  localDeviceEditingId: null,
  realtimeTimer: null
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

document.addEventListener("DOMContentLoaded", () => {
  $("#tokenInput").value = state.token;
  bindEvents();
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
  $("#executeCommandBtn").addEventListener("click", executeCommand);
  $("#loadShadowBtn").addEventListener("click", loadShadow);
  $("#saveDesiredBtn").addEventListener("click", saveDesired);
  $("#clearDesiredBtn").addEventListener("click", clearDesired);
}

async function refreshAll() {
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
    throw apiError(body.message || `业务错误 ${body.code}`, body, response.status);
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

  renderCards("#overviewCards", [
    ["设备数", stats.deviceCount ?? "-"],
    ["点位数", stats.pointCount ?? "-"],
    ["连接配置", stats.connectionCount ?? "-"],
    ["运行设备", Array.isArray(running) ? running.length : "-"],
    ["缓存命中率", percent(cache.totalHitRate)],
    ["健康状态", healthData.status || healthData.overallStatus || "-"],
    ["同步监听", summary.listenerCount ?? "-"],
    ["下次同步", formatTs(summary.nextSyncTime)]
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
  const rows = state.devices.map((device) => {
    const id = device.id || device.deviceId;
    const address = [device.ipAddress, device.port].filter(Boolean).join(":") || "-";
    const local = isLocalDevice(device);
    const runtime = getRuntimeStatus(id);
    const status = resolveDeviceStatus(device, runtime);
    const source = local
      ? `<span class="badge badge-local">本地临时设备</span>`
      : `<span class="badge">远端/同步</span>`;
    const editButtons = local
      ? `<button onclick="editLocalDevice('${escapeAttr(id)}')">修改</button>
         <button onclick="deleteLocalDevice('${escapeAttr(id)}')" class="danger">删除</button>`
      : "";
    return `
      <tr>
        <td><strong>${escapeHtml(device.deviceName || id)}</strong><br><code>${escapeHtml(id)}</code></td>
        <td>${source}</td>
        <td>${escapeHtml(device.protocolType || device.connectionType || "-")}</td>
        <td>${escapeHtml(address)}</td>
        <td>${device.collectionInterval ?? "-"} ms</td>
        <td>${renderDeviceStatus(status, runtime, device)}</td>
        <td>
          <div class="inline-actions">
            <button onclick="startDevice('${escapeAttr(id)}')">启动</button>
            <button onclick="stopDevice('${escapeAttr(id)}')" class="danger">停止</button>
            <button onclick="showDeviceStatus('${escapeAttr(id)}')">状态</button>
            <button onclick="showDiff('${escapeAttr(id)}')">diff</button>
            ${editButtons}
          </div>
        </td>
      </tr>`;
  }).join("");
  $("#deviceRows").innerHTML = rows || `<tr><td colspan="7">暂无设备配置</td></tr>`;
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
  });
  syncProtocolSelectionToDevice(false);
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

function renderProtocolMeta(protocol) {
  if (!protocol) {
    return "No protocol metadata";
  }
  const status = protocol.implemented ? "Implemented" : "Placeholder";
  return `
    <strong>${escapeHtml(protocol.title)}</strong>
    <span class="${protocol.implemented ? "status-good" : "status-bad"}">${status}</span>
    <p>${escapeHtml(protocol.description || "")}</p>
    <p>Aliases: ${(protocol.aliases || []).map(escapeHtml).join(", ") || "-"}</p>
    <p>Address hints: ${(protocol.pointAddressHints || []).map((item) => `<code>${escapeHtml(item)}</code>`).join(" ") || "-"}</p>
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

function renderField(field, formId) {
  const required = field.required ? `<span class="field-required">*</span>` : "";
  const hint = field.requiredWhen ? `<span class="field-hint">${escapeHtml(field.requiredWhen)}</span>` : "";
  const note = fieldHelpText(field);
  const value = fieldDefaultValue(field);
  const inputName = escapeAttr(field.name);
  let control;
  if (field.type === "select" || field.type === "boolean") {
    const options = field.options && field.options.length ? field.options : ["true", "false"];
    control = `<select name="${inputName}" data-form-id="${escapeAttr(formId)}">${options.map((option) =>
      `<option value="${escapeAttr(option)}" ${String(option) === String(value) ? "selected" : ""}>${escapeHtml(option)}</option>`
    ).join("")}</select>`;
  } else if (field.type === "object") {
    control = `<textarea name="${inputName}" data-form-id="${escapeAttr(formId)}" rows="4">${escapeHtml(value || "{}")}</textarea>`;
  } else {
    const inputType = field.type === "password" ? "password" : field.type === "number" ? "number" : "text";
    control = `<input name="${inputName}" data-form-id="${escapeAttr(formId)}" type="${inputType}" value="${escapeAttr(value)}">`;
  }
  return `
    <label data-field="${inputName}" data-required="${field.required ? "true" : "false"}" data-required-when="${escapeAttr(field.requiredWhen || "")}">
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
    container.innerHTML = "<p>No configurable connection fields for this protocol.</p>";
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
  $("#localProtocolMeta").innerHTML = renderProtocolMeta(state.currentLocalProtocol);
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
    baseCollectionInterval: adaptiveDefaults.baseCollectionInterval,
    currentCollectionInterval: adaptiveDefaults.baseCollectionInterval,
    minCollectionInterval: adaptiveDefaults.minCollectionInterval,
    maxCollectionInterval: adaptiveDefaults.maxCollectionInterval,
    pointChangeThreshold: adaptiveDefaults.pointChangeThreshold,
    additionalConfig: {
      reportEnabled: true,
      reportField: "temperature"
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
  $("#protocolCount").textContent = `${visibleProtocols.length} protocols`;
  $("#protocolSelect").innerHTML = visibleProtocols
    .map((protocol) => `<option value="${protocol.protocol}">${protocol.title} (${protocol.protocol})</option>`)
    .join("");
  $("#localProtocolSelect").innerHTML = visibleProtocols
    .map((protocol) => `<option value="${protocol.protocol}">${protocol.title} (${protocol.protocol})</option>`)
    .join("");
  renderLocalProtocolSelection();
  renderSelectedProtocol();
  syncProtocolSelectionToDevice(false);
}

function renderSelectedProtocol() {
  const protocolCode = canonicalProtocolForUi($("#protocolSelect").value);
  $("#protocolSelect").value = protocolCode;
  state.currentProtocol = getProtocolSchema(protocolCode);
  const protocol = state.currentProtocol;
  if (!protocol) {
    $("#connectionForm").innerHTML = "";
    $("#protocolMeta").textContent = "No protocol metadata";
    return;
  }
  $("#protocolMeta").innerHTML = renderProtocolMeta(protocol);
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
  toast(`已请求启动 ${deviceId}`);
}

async function stopDevice(deviceId) {
  await callApi(`/api/device/${encodeURIComponent(deviceId)}/stop`, { method: "POST" });
  await Promise.all([loadDevices(), loadOverview(), loadMonitor()]);
  toast(`已请求停止 ${deviceId}`);
}

async function showDeviceStatus(deviceId) {
  const body = await callApi(`/api/device/${encodeURIComponent(deviceId)}/status`);
  $("#monitorView").textContent = JSON.stringify(body, null, 2);
  location.hash = "#monitor";
}

async function showDiff(deviceId) {
  $("#connectionDeviceSelect").value = deviceId;
  syncProtocolSelectionToDevice(false);
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
    $("#toggleRealtimeBtn").textContent = "开始轮询";
    return;
  }
  loadRealtime();
  state.realtimeTimer = setInterval(loadRealtime, 3000);
  $("#toggleRealtimeBtn").textContent = "停止轮询";
}

async function loadRealtime() {
  const deviceId = $("#realtimeDeviceSelect").value;
  if (!deviceId) {
    return;
  }
  const body = await callApi(`/api/data/device/${encodeURIComponent(deviceId)}`);
  const values = body.data || {};
  const rows = Object.values(values).map((point) => `
    <tr>
      <td>${escapeHtml(point.pointName || point.pointId || "-")}</td>
      <td><code>${escapeHtml(point.pointCode || "-")}</code></td>
      <td><strong>${escapeHtml(formatValue(point.value))}</strong></td>
      <td class="${point.qualityAcceptable === false ? "status-bad" : "status-good"}">${escapeHtml(point.quality || "-")}</td>
      <td>${escapeHtml(formatValue(point.rawValue))}</td>
      <td>${point.processingTime ?? "-"} ms</td>
    </tr>
  `).join("");
  $("#realtimeRows").innerHTML = rows || `<tr><td colspan="6">暂无实时数据</td></tr>`;
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
    ["总访问", cacheData.totalAccess ?? "-"],
    ["L1 命中率", percent(cacheData.level1HitRate)],
    ["活跃连接", deviceData.activeConnections ?? "-"],
    ["缺失连接", Array.isArray(deviceData.missingConnections) ? deviceData.missingConnections.length : "-"],
    ["堆内存", bytes(systemData.heapUsed)],
    ["线程数", systemData.threadCount ?? "-"],
    ["系统 CPU", percent(systemData.systemCpuLoad)],
    ["异常数", errorData.totalCount ?? errorData.totalErrors ?? "-"]
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
  $(selector).innerHTML = items.map(([label, value]) => `
    <div class="card">
      <small>${escapeHtml(label)}</small>
      <strong>${escapeHtml(String(value ?? "-"))}</strong>
    </div>
  `).join("");
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
