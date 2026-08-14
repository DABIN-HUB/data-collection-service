(() => {
  "use strict";

  const ROUTES = Object.freeze({
    overview: { title: "控制台总览", type: "page", target: "overview", load: () => loadOverview() },
    realtime: { title: "实时数据查询", type: "exact", target: "realtimePage", load: loadExactRealtimePage },
    alarm: { title: "告警总览", type: "page", target: "alarm", load: loadAlarmPage },
    device: { title: "设备管理", type: "exact", target: "devicePage", load: loadExactDevicePage },
    collect: { title: "数据采集配置", type: "exact", target: "collectPage", load: loadExactCollectionPage },
    cloud: { title: "云平台配置", type: "page", target: "cloud", load: loadCloudPage },
    control: { title: "手动控制", type: "workspace", load: loadControlPage },
    shadow: { title: "设备影子", type: "workspace", load: loadShadowPage },
    diag: { title: "系统诊断", type: "exact", target: "diagPage", load: loadExactDiagnosticPage },
    log: { title: "日志", type: "page", target: "log", load: loadLogPage },
    network: { title: "网络检测", type: "page", target: "network", load: loadNetworkPage }
  });

  const ROUTE_ALIASES = Object.freeze({
    devices: "device",
    protocols: "collect",
    monitor: "diag"
  });

  const consoleState = {
    route: "overview",
    alarms: [],
    acknowledgements: {},
    selectedAlarmId: "",
    logs: [],
    logTimer: null,
    routeRequestId: 0
  };

  document.addEventListener("DOMContentLoaded", initializeModaoConsole);

  function initializeModaoConsole() {
    bindModaoNavigation();
    bindExactPageEvents();
    bindAlarmEvents();
    bindCloudEvents();
    bindLogEvents();
    bindNetworkEvents();
    window.addEventListener("hashchange", routeFromLocation);
    routeFromLocation();
  }

  function bindModaoNavigation() {
    document.querySelectorAll("[data-nav]").forEach((link) => {
      link.addEventListener("click", (event) => {
        event.preventDefault();
        navigateTo(link.dataset.nav);
      });
    });
    document.querySelector("#topRefreshBtn")?.addEventListener("click", () => refreshCurrentRoute(true));
  }

  function bindExactPageEvents() {
    document.querySelector("#exactRealtimeRefreshBtn")?.addEventListener("click", loadExactRealtimePage);
    document.querySelector("#exactRealtimeToggleBtn")?.addEventListener("click", toggleExactRealtime);
    document.querySelector("#exactRealtimeDeviceSelect")?.addEventListener("change", handleExactRealtimeDeviceChange);
    document.querySelector("#exactRealtimeSearch")?.addEventListener("input", renderExactRealtimeRows);
    document.querySelector("#exactReloadDevicesBtn")?.addEventListener("click", loadExactDevicePage);
    document.querySelector("#exactOpenDeviceBtn")?.addEventListener("click", () => {
      if (window.localDeviceEditor?.open) {
        window.localDeviceEditor.open();
        return;
      }
      window.openLocalDeviceForm?.();
    });
    document.querySelector("#exactSyncDevicesBtn")?.addEventListener("click", syncConfig);
    document.querySelector("#exactDeviceSearch")?.addEventListener("input", renderExactDeviceCards);
    document.querySelector("#exactProtocolFilter")?.addEventListener("change", renderExactDeviceCards);
    document.querySelector("#exactStatusFilter")?.addEventListener("change", renderExactDeviceCards);
    document.querySelector("#exactDeviceCards")?.addEventListener("click", handleExactDeviceAction);
    document.querySelector("#exactExportConfigBtn")?.addEventListener("click", exportConfig);
    document.querySelector("#exactProtocolRows")?.addEventListener("click", handleExactProtocolAction);
    document.querySelector("#exactRunDiagnosticBtn")?.addEventListener("click", loadExactDiagnosticPage);
  }

  function bindAlarmEvents() {
    document.querySelector("#refreshAlarmBtn")?.addEventListener("click", loadAlarmPage);
    document.querySelector("#alarmLevelFilter")?.addEventListener("change", loadAlarmPage);
    document.querySelector("#alarmTimeFilter")?.addEventListener("change", loadAlarmPage);
    document.querySelector("#alarmSearchInput")?.addEventListener("input", renderAlarmRows);
    document.querySelector("#alarmRows")?.addEventListener("click", (event) => {
      const button = event.target.closest("[data-alarm-ack]");
      if (button) {
        openAlarmAcknowledgement(button.dataset.alarmAck);
      }
    });
    document.querySelector("#cancelAlarmAckBtn")?.addEventListener("click", closeAlarmAcknowledgement);
    document.querySelector("#confirmAlarmAckBtn")?.addEventListener("click", submitAlarmAcknowledgement);
    document.querySelector("#alarmAckDialog")?.addEventListener("click", (event) => {
      if (event.target.id === "alarmAckDialog") {
        closeAlarmAcknowledgement();
      }
    });
  }

  function bindCloudEvents() {
    document.querySelector("#refreshCloudBtn")?.addEventListener("click", loadCloudPage);
  }

  function bindLogEvents() {
    document.querySelector("#refreshLogsBtn")?.addEventListener("click", loadLogPage);
    document.querySelector("#logLevelFilter")?.addEventListener("change", loadLogPage);
    document.querySelector("#logModuleFilter")?.addEventListener("change", loadLogPage);
    document.querySelector("#logSearchInput")?.addEventListener("input", renderLogRows);
    document.querySelector("#logAutoRefresh")?.addEventListener("change", syncLogTimer);
    document.querySelector("#exportLogsBtn")?.addEventListener("click", exportLogs);
  }

  function bindNetworkEvents() {
    document.querySelector("#networkDeviceSelect")?.addEventListener("change", syncNetworkTargetFromDevice);
    document.querySelector("#networkType")?.addEventListener("change", syncNetworkFieldState);
    document.querySelector("#startNetworkTestBtn")?.addEventListener("click", executeNetworkTest);
  }

  function navigateTo(routeName) {
    const normalized = normalizeRoute(routeName);
    if (window.location.hash === `#${normalized}`) {
      applyRoute(normalized);
      return;
    }
    window.location.hash = normalized;
  }

  function routeFromLocation() {
    applyRoute(normalizeRoute(window.location.hash.slice(1)));
  }

  function normalizeRoute(routeName) {
    const value = String(routeName || "overview").trim().toLowerCase();
    const canonical = ROUTE_ALIASES[value] || value;
    return ROUTES[canonical] ? canonical : "overview";
  }

  function applyRoute(routeName) {
    const route = ROUTES[routeName];
    consoleState.route = routeName;
    consoleState.routeRequestId += 1;
    document.body.dataset.consoleRoute = routeName;
    stopRouteTimers(routeName);
    updateNavigation(routeName, route.title);
    updateRouteVisibility(route);
    activateLegacyWorkspace(routeName);
    window.scrollTo(0, 0);
    window.requestAnimationFrame(() => window.scrollTo(0, 0));
    window.setTimeout(() => window.scrollTo(0, 0), 0);
    refreshCurrentRoute(false);
  }

  function updateNavigation(routeName, title) {
    document.querySelectorAll("[data-nav]").forEach((link) => {
      link.classList.toggle("is-active", normalizeRoute(link.dataset.nav) === routeName);
    });
    const titleNode = document.querySelector("#consolePageTitle");
    if (titleNode) {
      titleNode.textContent = title;
    }
    document.title = `${title} - 工业数据采集平台`;
  }

  function updateRouteVisibility(route) {
    const workspace = document.querySelector(".workspace-grid");
    const pageIds = ["overview", "alarm", "cloud", "log", "network", "realtimePage", "devicePage", "collectPage", "diagPage"];
    pageIds.forEach((id) => {
      const page = document.getElementById(id);
      if (page) {
        page.classList.toggle("hidden", !["page", "exact"].includes(route.type) || route.target !== id);
      }
    });
    workspace?.classList.toggle("hidden", route.type !== "workspace");
  }

  function activateLegacyWorkspace(routeName) {
    if (routeName === "collect") {
      activateWorkbenchTab("protocol");
      return;
    }
    if (["realtime", "control", "shadow"].includes(routeName)) {
      activateWorkbenchTab("points");
    }
    if (routeName === "control") {
      activateConsoleTab("control");
    } else if (routeName === "shadow") {
      activateConsoleTab("shadow");
    }
  }

  function refreshCurrentRoute(showSuccess) {
    const requestId = consoleState.routeRequestId;
    const route = ROUTES[consoleState.route];
    if (!route || typeof route.load !== "function") {
      return;
    }
    Promise.resolve(route.load())
      .then(() => {
        if (showSuccess && requestId === consoleState.routeRequestId) {
          toast(`${route.title}已刷新`);
        }
      })
      .catch((error) => {
        if (requestId === consoleState.routeRequestId) {
          toast(localizeConsoleError(error, `${route.title}加载失败`), true);
        }
      });
  }

  function stopRouteTimers(routeName) {
    if (routeName !== "log" && consoleState.logTimer) {
      window.clearInterval(consoleState.logTimer);
      consoleState.logTimer = null;
    }
  }

  async function loadExactRealtimePage() {
    const stateNode = document.querySelector("#exactRealtimeState");
    try {
      if (!state.devices.length) {
        await loadDevices();
      }
      syncExactDeviceOptions();
      const exactSelect = document.querySelector("#exactRealtimeDeviceSelect");
      const deviceId = exactSelect?.value || state.selectedDeviceId || state.devices[0]?.deviceId || state.devices[0]?.id || "";
      if (deviceId) {
        state.selectedDeviceId = deviceId;
        syncDeviceSelectValues(deviceId);
        if (exactSelect) {
          exactSelect.value = deviceId;
        }
        await loadRealtime();
      }
      renderExactRealtimeRows();
      stateNode?.classList.add("hidden");
    } catch (error) {
      if (stateNode) {
        stateNode.textContent = localizeConsoleError(error, "实时数据加载失败");
        stateNode.classList.remove("hidden");
      }
      renderExactRealtimeRows();
      throw error;
    }
  }

  function syncExactDeviceOptions() {
    const select = document.querySelector("#exactRealtimeDeviceSelect");
    if (!select) {
      return;
    }
    const current = select.value || state.selectedDeviceId;
    select.innerHTML = state.devices.length
      ? state.devices.map((device) => {
        const deviceId = device.deviceId || device.id || "";
        return `<option value="${escapeAttr(deviceId)}">${escapeHtml(device.deviceName || deviceId)}</option>`;
      }).join("")
      : `<option value="">暂无设备</option>`;
    const exists = Array.from(select.options).some((option) => option.value === current);
    select.value = exists ? current : (select.options[0]?.value || "");
  }

  async function handleExactRealtimeDeviceChange(event) {
    const deviceId = String(event.target.value || "");
    state.selectedDeviceId = deviceId;
    syncDeviceSelectValues(deviceId);
    await loadExactRealtimePage();
  }

  function toggleExactRealtime() {
    state.realtimeAutoRefreshEnabled = !state.realtimeAutoRefreshEnabled;
    const button = document.querySelector("#exactRealtimeToggleBtn");
    button?.classList.toggle("is-active", state.realtimeAutoRefreshEnabled);
    syncRealtimeTimer();
    if (state.realtimeAutoRefreshEnabled) {
      loadExactRealtimePage().catch((error) => toast(localizeConsoleError(error, "实时数据加载失败"), true));
    }
  }

  function renderExactRealtimeRows() {
    const target = document.querySelector("#exactRealtimeRows");
    if (!target) {
      return;
    }
    const keyword = String(document.querySelector("#exactRealtimeSearch")?.value || "").trim().toLowerCase();
    const device = state.devices.find((item) => (item.deviceId || item.id) === state.selectedDeviceId);
    const points = safeArray(state.realtimeRawPoints).filter((point) => {
      if (!keyword) {
        return true;
      }
      return [point.pointName, point.pointCode, point.pointId, point.address, point.registerAddress]
        .filter(Boolean)
        .some((value) => String(value).toLowerCase().includes(keyword));
    });
    if (!state.selectedDeviceId) {
      target.innerHTML = `<tr><td colspan="11" class="exact-empty">请选择设备后查看实时数据</td></tr>`;
      return;
    }
    if (!points.length) {
      target.innerHTML = `<tr><td colspan="11" class="exact-empty">当前设备暂无匹配的实时数据</td></tr>`;
      return;
    }
    target.innerHTML = points.map((point) => {
      const address = point.address || point.registerAddress || point.pointAddress || "-";
      const scale = point.scalingFactor ?? point.scale ?? point.factor ?? "-";
      const quality = realtimePointQualityText(point);
      const qualityClass = realtimePointQualityStatusClass(point).includes("good") ? "is-good" : (realtimePointQualityStatusClass(point).includes("bad") ? "is-bad" : "");
      return `<tr>
        <td>${escapeHtml(point.pointName || point.pointId || "-")}</td>
        <td>${escapeHtml(device?.deviceName || state.selectedDeviceId)}</td>
        <td>${escapeHtml(point.dataType || point.driverDataType || point.type || "-")}</td>
        <td><code>${escapeHtml(formatValue(address))}</code></td>
        <td>${escapeHtml(point.readWrite || point.accessMode || "-")}</td>
        <td>${escapeHtml(formatValue(scale))}</td>
        <td><strong>${escapeHtml(realtimePointValueText(point))}</strong></td>
        <td>${escapeHtml(point.unit || "-")}</td>
        <td>${escapeHtml(formatTs(point.collectTime || point.collectionTime || point.timestamp || point.ts))}</td>
        <td><span class="quality-badge ${qualityClass}">${escapeHtml(quality)}</span></td>
        <td>${escapeHtml(realtimePointProcessingTimeText(point))}</td>
      </tr>`;
    }).join("");
  }

  async function loadExactDevicePage() {
    try {
      await loadDevices();
      syncExactDeviceFilters();
      renderExactDeviceCards();
    } catch (error) {
      const target = document.querySelector("#exactDeviceCards");
      if (target) {
        target.innerHTML = `<div class="exact-empty">${escapeHtml(localizeConsoleError(error, "设备列表加载失败"))}</div>`;
      }
      throw error;
    }
  }

  function syncExactDeviceFilters() {
    const protocolFilter = document.querySelector("#exactProtocolFilter");
    if (!protocolFilter) {
      return;
    }
    const current = protocolFilter.value;
    const protocols = Array.from(new Set(state.devices
      .map((device) => canonicalProtocolForUi(device.protocolType || device.connectionType || ""))
      .filter(Boolean))).sort();
    protocolFilter.innerHTML = `<option value="">全部协议</option>${protocols
      .map((protocol) => `<option value="${escapeAttr(protocol)}">${escapeHtml(protocol)}</option>`)
      .join("")}`;
    protocolFilter.value = protocols.includes(current) ? current : "";
  }

  function renderExactDeviceCards() {
    const target = document.querySelector("#exactDeviceCards");
    if (!target) {
      return;
    }
    const keyword = String(document.querySelector("#exactDeviceSearch")?.value || "").trim().toLowerCase();
    const protocol = String(document.querySelector("#exactProtocolFilter")?.value || "");
    const statusFilter = String(document.querySelector("#exactStatusFilter")?.value || "");
    const devices = state.devices.filter((device) => {
      const deviceId = device.deviceId || device.id || "";
      const runtime = getRuntimeStatus(deviceId);
      const status = resolveDeviceStatus(device, runtime);
      const textMatch = !keyword || [deviceId, device.deviceName, device.protocolType, device.connectionType, device.ipAddress]
        .filter(Boolean)
        .some((value) => String(value).toLowerCase().includes(keyword));
      const protocolMatch = !protocol || canonicalProtocolForUi(device.protocolType || device.connectionType || "") === protocol;
      const statusMatch = !statusFilter || status === statusFilter || (statusFilter === "ONLINE" && status === "RUNNING");
      return textMatch && protocolMatch && statusMatch;
    });
    const summary = document.querySelector("#exactDeviceSummary");
    if (summary) {
      summary.lastChild.textContent = `${devices.length}/${state.devices.length} 台设备`;
    }
    if (!devices.length) {
      target.innerHTML = `<div class="exact-empty">${state.devices.length ? "没有匹配的设备" : "暂无设备配置"}</div>`;
      return;
    }
    target.innerHTML = devices.map((device) => {
      const deviceId = device.deviceId || device.id || "";
      const runtime = getRuntimeStatus(deviceId);
      const status = resolveDeviceStatus(device, runtime);
      const statusText = localizeDeviceStatus(status);
      const statusClass = status === "ONLINE" || status === "RUNNING" ? "is-online" : (status === "ERROR" ? "is-error" : "");
      const address = [device.ipAddress, device.port].filter((value) => value !== null && value !== undefined && value !== "").join(":") || "-";
      return `<article class="exact-device-card" data-device-id="${escapeAttr(deviceId)}">
        <div class="exact-device-main"><h3>${escapeHtml(device.deviceName || deviceId)}</h3><p>${escapeHtml(deviceId)} · ${isLocalDevice(device) ? "本地临时" : "远端同步"}</p></div>
        <div class="exact-device-meta"><strong>${escapeHtml(device.protocolType || device.connectionType || "-")}</strong><span>连接地址 ${escapeHtml(address)}</span></div>
        <div class="exact-device-meta"><span class="status-badge ${statusClass}">${escapeHtml(statusText)}</span><span>采集周期 ${escapeHtml(String(device.collectionInterval ?? "-"))} ms</span></div>
        <div class="exact-device-actions">
          <button type="button" data-device-action="start">启动</button><button type="button" data-device-action="stop">停止</button>
          <button type="button" data-device-action="edit">编辑</button><button type="button" data-device-action="diff">差异</button>
          <button type="button" data-device-action="control">控制</button><button type="button" data-device-action="shadow">影子</button>
        </div>
      </article>`;
    }).join("");
  }

  async function handleExactDeviceAction(event) {
    const button = event.target.closest("[data-device-action]");
    const card = button?.closest("[data-device-id]");
    if (!button || !card) {
      return;
    }
    const deviceId = card.dataset.deviceId;
    const device = state.devices.find((item) => (item.deviceId || item.id) === deviceId);
    switch (button.dataset.deviceAction) {
      case "start":
        await startDevice(deviceId);
        await loadExactDevicePage();
        break;
      case "stop":
        await stopDevice(deviceId);
        await loadExactDevicePage();
        break;
      case "edit":
        if (device && isLocalDevice(device)) {
          if (window.localDeviceEditor?.edit) {
            await window.localDeviceEditor.edit(deviceId);
          } else {
            await window.editLocalDevice?.(deviceId);
          }
        } else {
          openDeviceRoute(deviceId, "collect");
        }
        break;
      case "diff":
        await openExactDiffModal(deviceId);
        break;
      case "control":
      case "shadow":
        openDeviceRoute(deviceId, button.dataset.deviceAction);
        break;
      default:
        break;
    }
  }

  async function loadExactCollectionPage() {
    const [protocolResult, summaryResult] = await Promise.allSettled([
      loadProtocols(),
      callApi("/api/config/summary")
    ]);
    if (protocolResult.status === "rejected") {
      document.querySelector("#exactProtocolRows").innerHTML = `<tr><td colspan="6" class="exact-empty">${escapeHtml(localizeConsoleError(protocolResult.reason, "协议定义加载失败"))}</td></tr>`;
    } else {
      renderExactProtocolRows();
    }
    const summary = summaryResult.status === "fulfilled" ? dataOf(summaryResult.value) || {} : {};
    renderExactCollectionSummary(summary);
    if (protocolResult.status === "rejected" && summaryResult.status === "rejected") {
      throw protocolResult.reason;
    }
  }

  function renderExactCollectionSummary(summary) {
    const target = document.querySelector("#exactCollectionSummary");
    if (!target) {
      return;
    }
    const stats = summary.cacheStats || {};
    const items = [
      ["设备配置", `${stats.deviceCount ?? summary.deviceCount ?? state.devices.length ?? 0} 台`],
      ["点位总数", `${stats.pointCount ?? summary.pointCount ?? 0} 个`],
      ["连接配置", `${stats.connectionCount ?? summary.connectionCount ?? 0} 个`],
      ["配置来源", summary.configSource || summary.source || "当前运行配置"]
    ];
    target.innerHTML = items.map(([label, value]) => `<div class="exact-config-item"><span>${escapeHtml(label)}</span><strong>${escapeHtml(String(value))}</strong></div>`).join("");
  }

  function renderExactProtocolRows() {
    const target = document.querySelector("#exactProtocolRows");
    const protocols = safeArray(state.protocols).filter((protocol) => !HIDDEN_PROTOCOLS.has(protocol.protocol));
    const count = document.querySelector("#exactProtocolCount");
    if (count) {
      count.lastChild.textContent = `${protocols.length} 种协议`;
    }
    if (!target) {
      return;
    }
    if (!protocols.length) {
      target.innerHTML = `<tr><td colspan="6" class="exact-empty">当前没有可用的协议定义</td></tr>`;
      return;
    }
    target.innerHTML = protocols.map((protocol) => {
      const portField = safeArray(protocol.connectionFields).find((field) => field.name === "port");
      const defaultPort = protocol.defaultPort ?? portField?.defaultValue ?? "-";
      const mode = protocol.collectionMode || protocol.triggerMode || protocol.addressingMode || "轮询/协议驱动";
      const capability = protocol.implementationStatus || protocol.status || (protocol.implemented === false ? "未实现" : "已接入");
      return `<tr>
        <td><strong>${escapeHtml(protocol.title || protocol.protocol || "-")}</strong></td>
        <td><code>${escapeHtml(protocol.protocol || "-")}</code></td>
        <td>${escapeHtml(String(defaultPort))}</td>
        <td>${escapeHtml(String(mode))}</td>
        <td><span class="capability-badge">${escapeHtml(String(capability))}</span></td>
        <td><button type="button" data-protocol-action="configure" data-protocol="${escapeAttr(protocol.protocol || "")}">配置设备</button></td>
      </tr>`;
    }).join("");
  }

  function handleExactProtocolAction(event) {
    const button = event.target.closest("[data-protocol-action]");
    if (!button) {
      return;
    }
    openLocalDeviceForm();
    const select = document.querySelector("#localProtocolSelect");
    if (select && Array.from(select.options).some((option) => option.value === button.dataset.protocol)) {
      select.value = button.dataset.protocol;
      renderLocalProtocolSelection();
    }
  }

  async function loadExactDiagnosticPage() {
    const results = await Promise.allSettled([
      callApi("/health"),
      callApi("/monitor/system"),
      callApi("/monitor/devices"),
      callApi("/monitor/cache"),
      callApi("/monitor/perf/detail"),
      callApi("/monitor/report"),
      callApi("/api/config/summary")
    ]);
    const diagnostic = {
      health: settledData(results[0], {}),
      system: settledData(results[1], {}),
      devices: settledData(results[2], {}),
      cache: settledData(results[3], {}),
      performance: settledData(results[4], {}),
      report: settledData(results[5], {}),
      summary: settledData(results[6], {})
    };
    renderExactDiagnostic(diagnostic);
    if (results.every((result) => result.status === "rejected")) {
      throw results[0].reason;
    }
  }

  function renderExactDiagnostic(data) {
    const stats = data.summary.cacheStats || {};
    const totalDevices = stats.deviceCount ?? data.summary.deviceCount ?? data.devices.expectedConnections ?? 0;
    const pointCount = stats.pointCount ?? data.summary.pointCount ?? 0;
    const uptime = data.system.uptimeMillis ?? data.system.uptime ?? data.health.uptimeMillis;
    document.querySelector("#exactDiagnosticCards").innerHTML = [
      ["系统运行时间", uptime === undefined ? "-" : formatDurationMs(uptime)],
      ["设备配置总数", `${totalDevices} 台`],
      ["点位总数", `${pointCount} 个`],
      ["活跃连接", `${data.devices.activeConnections ?? 0} 个`]
    ].map(([label, value]) => `<div class="exact-diagnostic-card"><span>${escapeHtml(label)}</span><strong>${escapeHtml(String(value))}</strong></div>`).join("");

    const healthStatus = String(data.health.status || data.health.overallStatus || "UNKNOWN").toUpperCase();
    const cacheRate = ratioValue(data.cache.totalHitRate);
    const rejected = numberValue(data.performance.batchDispatchRejectedCount, 0) + numberValue(data.performance.collectRejectedCount, 0) + numberValue(data.performance.processRejectedCount, 0);
    const reportStatus = String(data.report.status || "UNKNOWN").toUpperCase();
    const rows = [
      ["应用服务", healthStatus === "UP" ? "正常" : "异常", healthStatus, healthStatus === "UP" ? "无需处理" : "检查应用健康检查明细"],
      ["设备连接", numberValue(data.devices.missingConnections?.length, 0) === 0 ? "正常" : "警告", `${data.devices.activeConnections ?? 0}/${data.devices.expectedConnections ?? totalDevices}`, "检查缺失连接和设备网络"],
      ["缓存服务", cacheRate === null || cacheRate >= 0.8 ? "正常" : "警告", cacheRate === null ? "指标不可用" : percent(cacheRate), "低命中率时检查缓存配置"],
      ["线程池拒绝", rejected === 0 ? "正常" : "异常", `${rejected} 次`, "检查队列容量、任务耗时和拒绝策略"],
      ["云端上报", ["UP", "ONLINE", "OK", "SUCCESS"].includes(reportStatus) ? "正常" : "警告", cloudStatusText(reportStatus), "检查处理器、Outbox 和 ACK 状态"]
    ];
    document.querySelector("#exactDiagnosticRows").innerHTML = rows.map(([name, status, current, suggestion]) => {
      const tone = status === "正常" ? "is-online" : (status === "异常" ? "is-error" : "");
      return `<tr><td>${escapeHtml(name)}</td><td><span class="status-badge ${tone}">${escapeHtml(status)}</span></td><td>${escapeHtml(String(current))}</td><td>${escapeHtml(suggestion)}</td></tr>`;
    }).join("");
    document.querySelector("#exactDiagnosticJson").textContent = JSON.stringify(data, null, 2);
  }

  async function loadCollectionPage() {
    await Promise.allSettled([loadProtocols(), reloadDevices()]);
    syncProtocolSelectionToDevice(false);
  }

  function loadControlPage() {
    syncDeviceContext(selectedDeviceId(), { loadRealtime: false });
    syncControlCommandExample();
  }

  async function loadShadowPage() {
    const deviceId = selectedDeviceId();
    syncDeviceContext(deviceId, { loadRealtime: false });
    if (deviceId) {
      await loadShadow();
    }
  }

  async function loadAlarmPage() {
    const stateNode = document.querySelector("#alarmPageState");
    if (stateNode) {
      stateNode.textContent = "正在加载告警";
    }
    const hours = boundedNumber(document.querySelector("#alarmTimeFilter")?.value, 24, 1, 24 * 31);
    const level = document.querySelector("#alarmLevelFilter")?.value || "";
    const params = new URLSearchParams({
      startTs: String(Date.now() - hours * 60 * 60 * 1000),
      endTs: String(Date.now()),
      limit: "500"
    });
    if (level) {
      params.set("level", level);
    }
    const body = await callApi(`/api/data/history/alarms?${params.toString()}`);
    const normalized = normalizeAlarmData(body);
    consoleState.alarms = safeArray(normalized.data);
    await loadAlarmAcknowledgements();
    renderAlarmSummary(consoleState.alarms);
    renderAlarmRows();
    if (stateNode) {
      stateNode.textContent = normalized.status === "disabled"
        ? "告警历史存储未启用"
        : `${consoleState.alarms.length} 条记录 · ${new Date().toLocaleTimeString()}`;
    }
  }

  async function loadAlarmAcknowledgements() {
    const alarmIds = consoleState.alarms.map(alarmIdentity);
    if (!alarmIds.length) {
      consoleState.acknowledgements = {};
      return;
    }
    const body = await callApi("/api/ops/alarms/acknowledgements/query", {
      method: "POST",
      body: JSON.stringify({ alarmIds })
    });
    consoleState.acknowledgements = dataOf(body) || {};
  }

  function openAlarmAcknowledgement(alarmId) {
    const alarm = consoleState.alarms.find((item) => alarmIdentity(item) === alarmId);
    if (!alarm || consoleState.acknowledgements[alarmId]) {
      return;
    }
    consoleState.selectedAlarmId = alarmId;
    setText("#alarmAckTarget", `${alarm.deviceId || alarm.device_id || "-"} / ${alarm.pointCode || alarm.point_code || alarm.pointId || alarm.point_id || "-"}`);
    const note = document.querySelector("#alarmAckNote");
    if (note) {
      note.value = "";
    }
    document.querySelector("#alarmAckDialog")?.classList.remove("hidden");
    window.setTimeout(() => note?.focus(), 0);
  }

  function closeAlarmAcknowledgement() {
    consoleState.selectedAlarmId = "";
    document.querySelector("#alarmAckDialog")?.classList.add("hidden");
  }

  async function submitAlarmAcknowledgement() {
    const alarmId = consoleState.selectedAlarmId;
    if (!alarmId) {
      return;
    }
    const button = document.querySelector("#confirmAlarmAckBtn");
    if (button) {
      button.disabled = true;
      button.textContent = "提交中...";
    }
    try {
      const body = await callApi(`/api/ops/alarms/${encodeURIComponent(alarmId)}/acknowledge`, {
        method: "POST",
        body: JSON.stringify({
          note: String(document.querySelector("#alarmAckNote")?.value || "").trim(),
          idempotencyKey: `console-${alarmId}`
        })
      });
      consoleState.acknowledgements[alarmId] = dataOf(body);
      closeAlarmAcknowledgement();
      renderAlarmRows();
      toast("告警已确认");
    } finally {
      if (button) {
        button.disabled = false;
        button.textContent = "提交确认";
      }
    }
  }

  function alarmIdentity(alarm) {
    const source = [
      alarm.deviceId || alarm.device_id,
      alarm.pointId || alarm.point_id || alarm.pointCode || alarm.point_code,
      alarm.ruleId || alarm.rule_id,
      alarm.eventTs || alarm.event_ts || alarm.timestamp || alarm.ts
    ].map((value) => String(value || "-")).join("|");
    return `alarm-${fnvHash(source, 2166136261)}${fnvHash(source, 2246822519)}`;
  }

  function fnvHash(value, seed) {
    let hash = seed >>> 0;
    for (let index = 0; index < value.length; index += 1) {
      hash ^= value.charCodeAt(index);
      hash = Math.imul(hash, 16777619);
    }
    return (hash >>> 0).toString(16).padStart(8, "0");
  }
  function renderAlarmSummary(alarms) {
    const counts = { CRITICAL: 0, WARNING: 0, INFO: 0, RECOVERED: 0 };
    alarms.forEach((alarm) => {
      const level = alarmLevel(alarm);
      if (Object.prototype.hasOwnProperty.call(counts, level)) {
        counts[level] += 1;
      }
      if (isRecoveredAlarm(alarm)) {
        counts.RECOVERED += 1;
      }
    });
    setText("#alarmCriticalCount", counts.CRITICAL);
    setText("#alarmWarningCount", counts.WARNING);
    setText("#alarmInfoCount", counts.INFO);
    setText("#alarmRecoveredCount", counts.RECOVERED);
  }

  function renderAlarmRows() {
    const container = document.querySelector("#alarmRows");
    if (!container) {
      return;
    }
    const keyword = String(document.querySelector("#alarmSearchInput")?.value || "").trim().toLowerCase();
    const rows = consoleState.alarms.filter((alarm) => {
      if (!keyword) {
        return true;
      }
      return [alarm.deviceId, alarm.device_id, alarm.pointId, alarm.point_id, alarm.pointCode,
        alarm.point_code, alarm.ruleId, alarm.rule_id, alarm.message]
        .some((value) => String(value || "").toLowerCase().includes(keyword));
    });
    if (!rows.length) {
      container.innerHTML = '<tr><td colspan="8"><div class="empty-state compact">当前条件下没有告警记录</div></td></tr>';
      return;
    }
    container.innerHTML = rows.map((alarm) => {
      const level = alarmLevel(alarm);
      const alarmId = alarmIdentity(alarm);
      const acknowledgement = consoleState.acknowledgements[alarmId];
      const recovered = isRecoveredAlarm(alarm);
      const status = recovered ? "已恢复" : acknowledgement ? "已确认" : "待确认";
      const operation = recovered || acknowledgement
        ? `<span class="modao-status ${recovered ? "success" : ""}">${escapeHtml(status)}</span>`
        : `<button type="button" data-alarm-ack="${escapeAttr(alarmId)}">确认</button>`;
      return `<tr>
        <td><span class="modao-status ${alarmTone(level)}">${escapeHtml(alarmLevelText(level))}</span></td>
        <td>${escapeHtml(formatTs(alarm.eventTs || alarm.event_ts || alarm.timestamp || alarm.ts))}</td>
        <td>${escapeHtml(alarm.deviceId || alarm.device_id || "-")}</td>
        <td>${escapeHtml(alarm.pointCode || alarm.point_code || alarm.pointId || alarm.point_id || "-")}</td>
        <td>${escapeHtml(alarm.ruleId || alarm.rule_id || alarm.ruleName || "-")}</td>
        <td>${escapeHtml(formatValue(alarm.currentValue ?? alarm.current_value ?? alarm.value))}</td>
        <td>${escapeHtml(status)}</td>
        <td>${operation}</td>
      </tr>`;
    }).join("");
  }

  function alarmLevel(alarm) {
    const level = String(alarm.level || alarm.alarmLevel || alarm.alarm_level || "INFO").toUpperCase();
    if (["CRITICAL", "FATAL", "ERROR", "HIGH"].includes(level)) {
      return "CRITICAL";
    }
    if (["WARN", "WARNING", "MEDIUM"].includes(level)) {
      return "WARNING";
    }
    return level === "RECOVERED" ? "RECOVERED" : "INFO";
  }

  function alarmLevelText(level) {
    return ({ CRITICAL: "严重", WARNING: "警告", INFO: "提示", RECOVERED: "已恢复" })[level] || "提示";
  }

  function alarmTone(level) {
    return ({ CRITICAL: "danger", WARNING: "warning", INFO: "", RECOVERED: "success" })[level] || "";
  }

  function isRecoveredAlarm(alarm) {
    return [alarm.status, alarm.alarmStatus, alarm.alarm_status, alarm.eventType, alarm.event_type]
      .some((value) => ["RECOVERED", "RESOLVED", "CLEAR", "CLEARED", "NORMAL"].includes(String(value || "").toUpperCase()));
  }

  async function loadCloudPage() {
    const stateNode = document.querySelector("#cloudPageState");
    if (stateNode) {
      stateNode.textContent = "正在读取上报链路";
    }
    const body = await callApi("/monitor/report");
    const report = dataOf(body) || {};
    renderCloudSummary(report);
    renderCloudHandlers(report);
    renderCloudStrategy(report);
    renderCloudRisks(report);
    if (stateNode) {
      stateNode.textContent = `更新于 ${formatTs(report.generatedAt || Date.now())}`;
    }
  }

  function renderCloudSummary(report) {
    const executor = report.executor || {};
    setText("#cloudLinkStatus", report.statusText || cloudStatusText(report.status));
    setText("#cloudPendingCount", report.outbox?.pendingCount ?? nonNegativeValue(executor.queueSize));
    setText("#cloudAckCount", report.outbox?.pendingAckCount ?? report.ackRuntime?.pendingCount ?? "-");
    setText("#cloudIsolatedCount", report.outbox?.isolatedCount ?? "-");
    setText("#exactCloudConnectionText", report.statusText || cloudStatusText(report.status));
    setText("#exactCloudConnectionDetail", report.enabled ? "云端上报已启用" : "云端上报未启用");
  }

  function renderCloudHandlers(report) {
    const container = document.querySelector("#cloudHandlerRows");
    if (!container) {
      return;
    }
    const protocols = safeArray(report.supportedProtocols);
    const statuses = report.handlersStatus || {};
    setText("#cloudHandlerCount", `${protocols.length} 个`);
    if (!protocols.length) {
      container.innerHTML = '<div class="empty-state compact">当前没有可用上报处理器</div>';
      return;
    }
    container.innerHTML = protocols.map((protocol) => {
      const detail = statuses[protocol] || {};
      const status = String(detail.status || detail.state || "READY").toUpperCase();
      const tone = ["ERROR", "FAILED", "DOWN"].includes(status) ? "danger" : ["WARN", "DEGRADED"].includes(status) ? "warning" : "success";
      return `<div class="modao-list-item"><div><strong>${escapeHtml(protocol)}</strong><small>${escapeHtml(detail.message || "处理器已装配")}</small></div><span class="modao-status ${tone}">${escapeHtml(localizeHandlerStatus(status))}</span></div>`;
    }).join("");
  }

  function renderCloudStrategy(report) {
    const container = document.querySelector("#cloudStrategyView");
    if (!container) {
      return;
    }
    const configured = report.configured || {};
    const batch = report.batch || {};
    const ack = report.ack || {};
    setInputValue("#exactCloudMode", report.mode || "-");
    setInputValue("#exactCloudProvider", report.cloudProvider || "-");
    setInputValue("#exactCloudAckCommit", ack.commitOn || "-");
    setInputValue("#exactCloudAckTimeout", ack.timeoutMs == null ? "-" : `${ack.timeoutMs} ms`);
    const values = [
      ["总开关", report.enabled ? "已启用" : "未启用"],
      ["上报模式", report.mode || "-"],
      ["云服务商", report.cloudProvider || "-"],
      ["可上报点位", `${configured.reportablePointCount ?? 0} / ${configured.pointCount ?? 0}`],
      ["批量聚合", batch.enabled ? `最多 ${batch.maxPropertiesPerPack ?? "-"} 属性` : "未启用"],
      ["ACK 提交点", ack.commitOn || "-"],
      ["ACK 超时", ack.timeoutMs == null ? "-" : `${ack.timeoutMs} ms`],
      ["可靠发件箱", report.outbox?.enabled ? "已启用" : "未启用"],
      ["最老待发消息", report.outbox?.oldestMessageAgeMs == null ? "-" : `${report.outbox.oldestMessageAgeMs} ms`]
    ];
    container.innerHTML = values.map(([label, value]) => `<div class="modao-property-item"><span>${escapeHtml(label)}</span><strong>${escapeHtml(value)}</strong></div>`).join("");
  }

  function renderCloudRisks(report) {
    const container = document.querySelector("#cloudRiskRows");
    if (!container) {
      return;
    }
    const risks = safeArray(report.risks);
    container.innerHTML = risks.length
      ? risks.map((risk) => `<div class="modao-risk-item"><strong>风险</strong><small>${escapeHtml(risk)}</small></div>`).join("")
      : '<div class="modao-risk-item"><strong>检查结果</strong><small>未发现已知上报风险</small></div>';
  }

  function cloudStatusText(status) {
    return ({ OK: "正常", WARN: "存在风险", ERROR: "异常", DISABLED: "未启用" })[String(status || "").toUpperCase()] || "未知";
  }

  function localizeHandlerStatus(status) {
    return ({ READY: "就绪", OK: "正常", RUNNING: "运行中", ERROR: "异常", FAILED: "失败", DOWN: "不可用", WARN: "警告", DEGRADED: "降级" })[status] || status;
  }

  async function loadLogPage() {
    const stateNode = document.querySelector("#logPageState");
    if (stateNode) {
      stateNode.textContent = "正在加载日志";
    }
    const params = new URLSearchParams({ limit: "500" });
    const level = document.querySelector("#logLevelFilter")?.value || "";
    const logger = String(document.querySelector("#logModuleFilter")?.value || "").trim();
    if (level) {
      params.set("level", level);
    }
    if (logger) {
      params.set("logger", logger);
    }
    const body = await callApi(`/api/ops/logs?${params.toString()}`);
    const payload = dataOf(body) || {};
    consoleState.logs = safeArray(payload.items || payload.logs || payload);
    renderLogRows();
    if (stateNode) {
      stateNode.textContent = `${consoleState.logs.length} 条 · ${new Date().toLocaleTimeString()}`;
    }
    syncLogTimer();
  }

  function renderLogRows() {
    const container = document.querySelector("#logRows");
    if (!container) {
      return;
    }
    const keyword = String(document.querySelector("#logSearchInput")?.value || "").trim().toLowerCase();
    const logs = consoleState.logs.filter((item) => !keyword || [item.message, item.logger, item.thread]
      .some((value) => String(value || "").toLowerCase().includes(keyword)));
    if (!logs.length) {
      container.innerHTML = '<div class="empty-state compact">当前条件下没有可显示日志</div>';
      return;
    }
    container.innerHTML = logs.map((item) => {
      const level = String(item.level || "INFO").toUpperCase();
      return `<div class="modao-log-row"><span class="modao-log-time">${escapeHtml(formatTs(item.timestamp))}</span><strong class="modao-log-level ${escapeAttr(level)}">${escapeHtml(level)}</strong><span class="modao-log-name" title="${escapeAttr(item.logger || "-")}">${escapeHtml(shortLoggerName(item.logger))}</span><span class="modao-log-message">${escapeHtml(item.message || "-")}</span></div>`;
    }).join("");
  }

  function syncLogTimer() {
    if (consoleState.logTimer) {
      window.clearInterval(consoleState.logTimer);
      consoleState.logTimer = null;
    }
    if (consoleState.route === "log" && document.querySelector("#logAutoRefresh")?.checked) {
      consoleState.logTimer = window.setInterval(() => loadLogPage().catch(() => {}), 5000);
    }
  }

  function exportLogs() {
    const content = consoleState.logs.map((item) => `${formatTs(item.timestamp)} ${item.level || "INFO"} ${item.logger || "-"} ${item.message || ""}`).join("\n");
    const blob = new Blob([content], { type: "text/plain;charset=utf-8" });
    const link = document.createElement("a");
    link.href = URL.createObjectURL(blob);
    link.download = `collector-logs-${Date.now()}.log`;
    link.click();
    URL.revokeObjectURL(link.href);
  }

  function shortLoggerName(logger) {
    const value = String(logger || "-");
    const parts = value.split(".");
    return parts.length > 2 ? parts.slice(-2).join(".") : value;
  }

  function loadNetworkPage() {
    const select = document.querySelector("#networkDeviceSelect");
    if (!select) {
      return;
    }
    const previous = select.value;
    select.innerHTML = '<option value="">本机</option>' + state.devices.map((device) => {
      const id = device.id || device.deviceId;
      return `<option value="${escapeAttr(id)}">${escapeHtml(device.deviceName || id)}（${escapeHtml(id)}）</option>`;
    }).join("");
    if (previous && Array.from(select.options).some((item) => item.value === previous)) {
      select.value = previous;
    }
    syncNetworkTargetFromDevice();
    syncNetworkFieldState();
  }

  function syncNetworkTargetFromDevice() {
    const deviceId = document.querySelector("#networkDeviceSelect")?.value || "";
    const device = getDeviceById(deviceId);
    const target = document.querySelector("#networkTarget");
    const port = document.querySelector("#networkPort");
    if (target) {
      target.value = device?.ipAddress || (deviceId ? "" : "127.0.0.1");
    }
    if (port) {
      port.value = device?.port || "";
    }
  }

  function syncNetworkFieldState() {
    const tcpMode = document.querySelector("#networkType")?.value === "TCP";
    const port = document.querySelector("#networkPort");
    if (port) {
      port.disabled = !tcpMode;
      port.required = tcpMode;
    }
  }

  async function executeNetworkTest() {
    const button = document.querySelector("#startNetworkTestBtn");
    const output = document.querySelector("#networkOutput");
    const stateNode = document.querySelector("#networkPageState");
    const payload = {
      type: document.querySelector("#networkType")?.value || "PING",
      deviceId: document.querySelector("#networkDeviceSelect")?.value || null,
      target: String(document.querySelector("#networkTarget")?.value || "").trim(),
      port: nullableNumber(document.querySelector("#networkPort")?.value),
      timeoutMs: boundedNumber(document.querySelector("#networkTimeout")?.value, 3000, 100, 10000)
    };
    if (!payload.target) {
      toast("请输入检测目标", true);
      return;
    }
    if (payload.type === "TCP" && !payload.port) {
      toast("TCP 检测需要填写有效端口", true);
      return;
    }
    if (button) {
      button.disabled = true;
      button.textContent = "检测中...";
    }
    if (output) {
      output.textContent = "正在执行受控网络检测，请稍候...";
    }
    if (stateNode) {
      stateNode.textContent = "检测执行中";
    }
    try {
      const body = await callApi("/api/ops/network/diagnose", { method: "POST", body: JSON.stringify(payload) });
      const result = dataOf(body) || {};
      if (output) {
        output.textContent = formatNetworkResult(result);
      }
      if (stateNode) {
        stateNode.textContent = result.reachable ? "检测通过" : "检测未通过";
      }
    } finally {
      if (button) {
        button.disabled = false;
        button.textContent = "开始检测";
      }
    }
  }

  function formatNetworkResult(result) {
    const values = [
      ["检测方式", result.type || "-"],
      ["检测目标", result.target || "-"],
      ["解析地址", result.resolvedAddress || "-"],
      ["目标端口", result.port ?? "-"],
      ["检测结论", result.reachable ? "可达" : "不可达"],
      ["处理耗时", result.durationMs == null ? "-" : `${result.durationMs} ms`],
      ["完成时间", formatTs(result.completedAt)],
      ["详细信息", result.message || "-"]
    ];
    const summary = values.map(([label, value]) => `${label.padEnd(6, "　")}：${value}`).join("\n");
    const details = safeArray(result.details);
    return details.length ? `${summary}\n\n路由明细：\n${details.join("\n")}` : summary;
  }

  function setText(selector, value) {
    const node = document.querySelector(selector);
    if (node) {
      node.textContent = value == null ? "-" : String(value);
    }
  }

  function setInputValue(selector, value) {
    const target = document.querySelector(selector);
    if (target) {
      target.value = value == null ? "-" : String(value);
    }
  }

  function nonNegativeValue(value) {
    const parsed = Number(value);
    return Number.isFinite(parsed) && parsed >= 0 ? parsed : "-";
  }

  function boundedNumber(value, fallback, minimum, maximum) {
    const parsed = Number(value);
    if (!Number.isFinite(parsed)) {
      return fallback;
    }
    return Math.min(maximum, Math.max(minimum, parsed));
  }

  function nullableNumber(value) {
    if (value === null || value === undefined || String(value).trim() === "") {
      return null;
    }
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
  }

  function openDeviceRoute(deviceId, routeName) {
    Promise.resolve(syncDeviceContext(deviceId, { loadRealtime: false }))
      .then(() => {
        if (["control", "shadow"].includes(routeName)) {
          openExactOperationModal(routeName);
          if (routeName === "shadow") {
            return loadShadow();
          }
          return null;
        }
        navigateTo(routeName);
        return null;
      })
      .catch((error) => toast(localizeConsoleError(error, "设备工作区打开失败"), true));
  }

  function localizeConsoleError(error, fallback) {
    const rawMessage = String(error?.message || error || "").trim();
    const normalized = rawMessage.toLowerCase();
    if (!normalized) {
      return fallback;
    }
    if (normalized.includes("missing credential")) {
      return "未配置运维访问令牌";
    }
    if (normalized.includes("invalid credential") || normalized.includes("unauthorized") || normalized === "http 401") {
      return "运维访问令牌无效";
    }
    if (normalized.includes("forbidden") || normalized === "http 403") {
      return "当前账号没有访问权限";
    }
    if (normalized.includes("failed to fetch") || normalized.includes("networkerror")) {
      return "无法连接后端服务";
    }
    return rawMessage;
  }

  function openExactOperationModal(panelId) {
    closeExactOperationModal();
    const panel = document.getElementById(panelId);
    if (!panel || !panel.parentNode) {
      toast("操作面板不存在", true);
      return;
    }
    const backdrop = document.createElement("div");
    backdrop.className = "exact-operation-backdrop";
    backdrop.innerHTML = `<section class="exact-operation-dialog"><header><h2>${panelId === "shadow" ? "设备影子" : "手动控制"}</h2><button type="button" data-close-operation>关闭</button></header><div class="exact-operation-body"></div></section>`;
    consoleState.operationPanel = {
      panel,
      parent: panel.parentNode,
      nextSibling: panel.nextSibling,
      backdrop
    };
    backdrop.querySelector(".exact-operation-body").appendChild(panel);
    document.body.appendChild(backdrop);
    activateConsoleTab(panelId);
    panel.classList.remove("hidden");
    panel.classList.add("exact-operation-panel");
    backdrop.querySelector("[data-close-operation]").addEventListener("click", closeExactOperationModal);
    backdrop.addEventListener("click", (event) => {
      if (event.target === backdrop) {
        closeExactOperationModal();
      }
    });
  }

  function closeExactOperationModal() {
    const modal = consoleState.operationPanel;
    if (!modal) {
      return;
    }
    modal.panel.classList.remove("exact-operation-panel");
    if (modal.nextSibling && modal.nextSibling.parentNode === modal.parent) {
      modal.parent.insertBefore(modal.panel, modal.nextSibling);
    } else {
      modal.parent.appendChild(modal.panel);
    }
    modal.backdrop.remove();
    consoleState.operationPanel = null;
  }

  async function openExactDiffModal(deviceId) {
    const body = await callApi(`/api/config/device/${encodeURIComponent(deviceId)}/diff`);
    const data = dataOf(body) || {};
    openExactInfoDialog(`配置差异 - ${deviceId}`, JSON.stringify(data, null, 2));
  }

  function openExactInfoDialog(title, content) {
    document.querySelector("#exactInfoDialog")?.remove();
    const backdrop = document.createElement("div");
    backdrop.id = "exactInfoDialog";
    backdrop.className = "exact-operation-backdrop";
    backdrop.innerHTML = `<section class="exact-operation-dialog exact-info-dialog"><header><h2>${escapeHtml(title)}</h2><button type="button" data-close-info>关闭</button></header><div class="exact-operation-body"><pre>${escapeHtml(content)}</pre></div></section>`;
    document.body.appendChild(backdrop);
    const close = () => backdrop.remove();
    backdrop.querySelector("[data-close-info]").addEventListener("click", close);
    backdrop.addEventListener("click", (event) => {
      if (event.target === backdrop) {
        close();
      }
    });
  }

  window.navigateConsole = navigateTo;
  window.openDeviceRoute = openDeviceRoute;
})();
