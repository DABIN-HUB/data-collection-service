(() => {
  state.devicePointConfigs = state.devicePointConfigs || {};
  state.devicePointConfigDirty = state.devicePointConfigDirty || {};
  state.pointConfigLoadingDeviceId = state.pointConfigLoadingDeviceId || null;
  state.pointConfigRenderedKey = state.pointConfigRenderedKey || null;

  const DEFAULT_POINT_DATA_TYPES = ["BOOLEAN", "BYTE", "SHORT", "INT", "LONG", "FLOAT", "DOUBLE", "STRING", "UINT16", "UINT32"];
  const POINT_READ_WRITE_OPTIONS = [
    { value: "R", label: "只读 (R)" },
    { value: "W", label: "只写 (W)" },
    { value: "RW", label: "读写 (RW)" }
  ];
  const POINT_COLLECTION_MODE_OPTIONS = [
    { value: "POLLING", label: "轮询" },
    { value: "SUBSCRIPTION", label: "订阅" },
    { value: "EVENT", label: "事件" }
  ];
  const POINT_STATUS_OPTIONS = [
    { value: 1, label: "启用" },
    { value: 0, label: "禁用" },
    { value: 2, label: "维护" },
    { value: 3, label: "异常" }
  ];
  const POINT_ENABLE_OPTIONS = [
    { value: 1, label: "开启" },
    { value: 0, label: "关闭" }
  ];
  const POINT_BOOLEAN_OPTIONS = [
    { value: "", label: "跟随后端默认" },
    { value: "true", label: "开启" },
    { value: "false", label: "关闭" }
  ];

  function hasOwn(target, key) {
    return Object.prototype.hasOwnProperty.call(target || {}, key);
  }

  function hasValue(value) {
    return value !== null && value !== undefined && (!(typeof value === "string") || value.trim() !== "");
  }

  function isPlainObject(value) {
    return Object.prototype.toString.call(value) === "[object Object]";
  }

  function cloneJson(value) {
    if (value === null || value === undefined) {
      return value;
    }
    return JSON.parse(JSON.stringify(value));
  }

  function getPath(target, path) {
    return String(path || "").split(".").reduce((current, key) => (current == null ? undefined : current[key]), target);
  }

  function setPath(target, path, value) {
    const keys = String(path || "").split(".");
    let current = target;
    keys.forEach((key, index) => {
      if (index === keys.length - 1) {
        current[key] = value;
        return;
      }
      if (!isPlainObject(current[key])) {
        current[key] = {};
      }
      current = current[key];
    });
  }

  function setPointConfigDirty(deviceId, dirty) {
    if (!deviceId) {
      return;
    }
    state.devicePointConfigDirty = {
      ...state.devicePointConfigDirty,
      [deviceId]: Boolean(dirty)
    };
  }

  function isPointConfigDirty(deviceId) {
    return Boolean(deviceId && state.devicePointConfigDirty[deviceId]);
  }

  function pointConfigKey(point, index = 0) {
    return String(point?.pointId || point?.pointCode || point?.address || `point-config-${index}`);
  }

  function pointIdentityTokens(point) {
    return [point?.pointId, point?.pointCode, point?.address, point?.registerAddress, point?.pointAddress]
      .filter(hasValue)
      .map((value) => String(value).trim());
  }

  function pointsMatch(left, right) {
    const leftTokens = new Set(pointIdentityTokens(left));
    return pointIdentityTokens(right).some((token) => leftTokens.has(token));
  }

  function prettyJsonString(value, fallbackValue = {}) {
    if (!hasValue(value)) {
      return JSON.stringify(fallbackValue, null, 2);
    }
    if (typeof value === "string") {
      try {
        return JSON.stringify(JSON.parse(value), null, 2);
      } catch (error) {
        return value;
      }
    }
    return JSON.stringify(value, null, 2);
  }

  function normalizePointConfig(point, index, deviceId) {
    const next = cloneJson(point) || {};
    next.deviceId = next.deviceId || deviceId;
    next.additionalConfig = isPlainObject(next.additionalConfig) ? { ...next.additionalConfig } : {};
    next.__configKey = pointConfigKey(next, index);
    return next;
  }

  function pointConfigsForDevice(deviceId) {
    return Array.isArray(state.devicePointConfigs[deviceId]) ? state.devicePointConfigs[deviceId] : [];
  }

  function selectedRuntimePoint() {
    return state.realtimePoints.find((item) => item.__pointKey === state.selectedRealtimePointKey) || null;
  }

  function selectedPointConfigContext() {
    const deviceId = selectedDeviceId();
    const device = getDeviceById(deviceId);
    const runtimePoint = selectedRuntimePoint();
    const configs = deviceId ? pointConfigsForDevice(deviceId) : [];
    const configPoint = runtimePoint ? configs.find((item) => pointsMatch(item, runtimePoint)) || null : null;
    const protocol = getProtocolSchema(deviceProtocolCode(device));
    return { deviceId, device, runtimePoint, configPoint, configs, protocol };
  }

  function setPointConfigState(text, tone = "idle") {
    const target = $("#pointConfigStateText");
    if (!target) {
      return;
    }
    target.textContent = text;
    target.className = `pill ${tone === "dirty" ? "point-state-dirty" : tone === "ready" ? "point-state-ready" : tone === "missing" ? "point-state-missing" : "subtle"}`;
  }

  function syncPointConfigActions({ canSave = false, canReload = false, saving = false } = {}) {
    const saveButton = $("#saveSelectedPointBtn");
    const reloadButton = $("#reloadSelectedPointBtn");
    if (saveButton) {
      saveButton.disabled = !canSave || saving;
      saveButton.textContent = saving ? "保存中..." : "保存点位";
    }
    if (reloadButton) {
      reloadButton.disabled = !canReload || saving;
    }
  }

  async function loadPointConfigs(deviceId = selectedDeviceId(), force = false) {
    if (!deviceId) {
      return [];
    }
    if (!force && hasOwn(state.devicePointConfigs, deviceId)) {
      return pointConfigsForDevice(deviceId);
    }

    state.pointConfigLoadingDeviceId = deviceId;
    renderSelectedPointInspector();
    try {
      const body = await callApi(`/api/config/device/${encodeURIComponent(deviceId)}/points`);
      const payload = dataOf(body) || {};
      const points = Array.isArray(payload.points) ? payload.points : [];
      state.devicePointConfigs[deviceId] = points.map((point, index) => normalizePointConfig(point, index, deviceId));
      setPointConfigDirty(deviceId, false);
      return state.devicePointConfigs[deviceId];
    } finally {
      if (state.pointConfigLoadingDeviceId === deviceId) {
        state.pointConfigLoadingDeviceId = null;
      }
      renderSelectedPointInspector();
    }
  }

  function pointQualityText(runtimePoint) {
    const quality = runtimePoint?.quality || (runtimePoint?.qualityAcceptable === false ? "BAD" : "GOOD");
    return localizePointQuality(quality);
  }

  function pointQualityClass(runtimePoint) {
    return runtimePoint?.qualityAcceptable === false ? "badge-alert" : "badge-remote";
  }

  function protocolDataTypeOptions(protocol) {
    const options = Array.isArray(protocol?.dataTypes) && protocol.dataTypes.length ? protocol.dataTypes : DEFAULT_POINT_DATA_TYPES;
    return options.map((value) => ({ value, label: value }));
  }

  function renderPointConfigField(field, point) {
    const value = getPath(point, field.path);
    const required = field.required ? ' <span class="field-required">*</span>' : "";
    const wide = field.fullWidth ? " point-config-field-wide" : "";
    const placeholder = field.placeholder ? ` placeholder="${escapeAttr(field.placeholder)}"` : "";
    const step = field.step !== null && field.step !== undefined ? ` step="${escapeAttr(field.step)}"` : field.control === "number" ? ' step="any"' : "";
    const min = field.min !== null && field.min !== undefined ? ` min="${escapeAttr(field.min)}"` : "";
    const dataAttrs = `data-point-field="${escapeAttr(field.path)}" data-value-type="${escapeAttr(field.valueType || "string")}"`;
    let control = "";

    if (field.control === "select") {
      control = `<select ${dataAttrs}>${(field.options || []).map((option) => renderFieldOption(option, value)).join("")}</select>`;
    } else if (field.control === "textarea") {
      const rows = Number(field.rows) > 0 ? Number(field.rows) : 3;
      const current = field.valueType === "json-object"
        ? prettyJsonString(value, {})
        : field.valueType === "json-string"
          ? prettyJsonString(value, [])
          : String(value ?? "");
      control = `<textarea ${dataAttrs} rows="${rows}"${placeholder}>${escapeHtml(current)}</textarea>`;
    } else {
      const inputType = field.control === "number" ? "number" : "text";
      control = `<input ${dataAttrs} type="${inputType}" value="${escapeAttr(value ?? "")}"${placeholder}${step}${min}>`;
    }

    return `
      <label class="point-config-field${wide}">
        <span class="field-label-text">${escapeHtml(field.label)}${required}</span>
        ${control}
        ${field.description ? `<span class="field-description">${escapeHtml(field.description)}</span>` : ""}
        <span class="field-error hidden"></span>
      </label>`;
  }

  function renderRuntimeMetric(label, value, tone = "default") {
    return `
      <div class="point-runtime-metric${tone === "good" ? " is-good" : tone === "bad" ? " is-bad" : ""}">
        <small>${escapeHtml(label)}</small>
        <strong>${escapeHtml(formatValue(value))}</strong>
      </div>`;
  }

  function buildPointConfigFieldGroups(context) {
    const protocol = context.protocol;
    return [
      {
        title: "基础信息",
        fields: [
          { path: "pointName", label: "点位名称", control: "text", valueType: "string", required: true },
          { path: "pointCode", label: "标识符", control: "text", valueType: "string", required: true },
          { path: "pointAlias", label: "点位别名（仅展示）", control: "text", valueType: "string" },
          { path: "address", label: "寄存器地址", control: "text", valueType: "string", required: true },
          { path: "groupId", label: "分组 ID", control: "text", valueType: "string" },
          { path: "remark", label: "描述", control: "textarea", valueType: "string", rows: 3, fullWidth: true }
        ]
      },
      {
        title: "采集与数据处理",
        fields: [
          { path: "dataType", label: "数据类型", control: "select", valueType: "string", options: protocolDataTypeOptions(protocol) },
          ...(protocol?.driverTypeEnabled ? [{ path: protocol.driverTypeField || "additionalConfig.driverDataType", label: protocol.driverTypeLabel || "协议原生类型", control: "select", valueType: "string", options: (protocol.driverDataTypes || []).map((value) => ({ value, label: value })) }] : []),
          { path: "readWrite", label: "读写类型", control: "select", valueType: "string", options: POINT_READ_WRITE_OPTIONS },
          { path: "collectionMode", label: "采集模式", control: "select", valueType: "string", options: POINT_COLLECTION_MODE_OPTIONS },
          { path: "status", label: "状态", control: "select", valueType: "integer", options: POINT_STATUS_OPTIONS },
          { path: "unit", label: "单位", control: "text", valueType: "string" },
          { path: "scalingFactor", label: "缩放因子", control: "number", valueType: "number", step: "0.0001" },
          { path: "offset", label: "偏移量", control: "number", valueType: "number", step: "0.0001" },
          { path: "deadband", label: "采集死区", control: "number", valueType: "number", step: "0.0001" },
          { path: "minValue", label: "最小值", control: "number", valueType: "number", step: "0.0001" },
          { path: "maxValue", label: "最大值", control: "number", valueType: "number", step: "0.0001" },
          { path: "precision", label: "小数位数", control: "number", valueType: "integer", step: "1", min: "0" },
          { path: "unitId", label: "单元地址（Unit ID）", control: "number", valueType: "integer", step: "1" },
          { path: "commonAddress", label: "公共地址", control: "number", valueType: "integer", step: "1" }
        ]
      },
      {
        title: "上报与缓存",
        fields: [
          { path: "priority", label: "优先级", control: "number", valueType: "integer", step: "1" },
          { path: "cacheEnabled", label: "启用缓存", control: "select", valueType: "integer", options: POINT_ENABLE_OPTIONS },
          { path: "cacheDuration", label: "缓存时长(秒)", control: "number", valueType: "integer", step: "1" },
          { path: "alarmEnabled", label: "启用告警", control: "select", valueType: "integer", options: POINT_ENABLE_OPTIONS },
          { path: "additionalConfig.reportEnabled", label: "参与设备上报", control: "select", valueType: "boolean", options: POINT_BOOLEAN_OPTIONS },
          { path: "additionalConfig.reportField", label: "云端属性（reportField）", control: "text", valueType: "string" },
          { path: "additionalConfig.changeThreshold", label: "变化阈值", control: "number", valueType: "number", step: "0.0001" },
          { path: "additionalConfig.changeMinIntervalMs", label: "变化最小间隔(ms)", control: "number", valueType: "integer", step: "1" },
          { path: "additionalConfig.eventEnabled", label: "事件上报", control: "select", valueType: "boolean", options: POINT_BOOLEAN_OPTIONS },
          { path: "additionalConfig.eventMinIntervalMs", label: "事件最小间隔(ms)", control: "number", valueType: "integer", step: "1" },
          { path: "additionalConfig.streamEnabled", label: "写入 Redis Stream", control: "select", valueType: "boolean", options: POINT_BOOLEAN_OPTIONS },
          { path: "additionalConfig.historyEnabled", label: "写入历史存储", control: "select", valueType: "boolean", options: POINT_BOOLEAN_OPTIONS }
        ]
      }
    ];
  }

  function renderSelectedPointWorkspace(context) {
    const { runtimePoint, configPoint, device, protocol, deviceId } = context;
    const qualityText = pointQualityText(runtimePoint);
    const qualityClass = pointQualityClass(runtimePoint);
    const heroName = configPoint?.pointName || runtimePoint?.pointName || configPoint?.pointCode || runtimePoint?.pointCode || "未命名点位";
    const heroCode = configPoint?.pointCode || runtimePoint?.pointCode || runtimePoint?.pointId || "-";
    const heroAddress = configPoint?.address || runtimePoint?.address || runtimePoint?.registerAddress || runtimePoint?.pointAddress || "-";
    const runtimeTone = runtimePoint?.qualityAcceptable === false ? "bad" : "good";
    const dirty = isPointConfigDirty(deviceId);

    if (!configPoint) {
      return `
        <div class="point-config-shell">
          <section class="point-config-hero">
            <div>
              <span class="label-chip">运行态摘要</span>
              <strong>${escapeHtml(heroName)}</strong>
              <p><code>${escapeHtml(heroCode)}</code> · ${escapeHtml(formatValue(heroAddress))}</p>
            </div>
            <div class="point-detail-hero-meta">
              <span class="pill subtle">${escapeHtml(device?.protocolType || device?.connectionType || "-")}</span>
              <span class="badge ${qualityClass}">${escapeHtml(qualityText)}</span>
            </div>
          </section>
          <section class="field-group point-config-card">
            <h3>尚未匹配到配置点位</h3>
            <p class="field-description">当前只拿到了运行态数据，没有从配置接口中匹配到同名点位。请先重新加载点位配置，或检查 pointId / pointCode / address 是否一致。</p>
            <div class="point-runtime-metrics">
              ${renderRuntimeMetric("当前值", runtimePoint?.value, runtimeTone)}
              ${renderRuntimeMetric("原始值", runtimePoint?.rawValue)}
              ${renderRuntimeMetric("质量", qualityText, runtimeTone)}
              ${renderRuntimeMetric("处理耗时", `${runtimePoint?.processingTime ?? "-"} ms`)}
            </div>
          </section>
        </div>`;
    }

    const groups = buildPointConfigFieldGroups(context);
    return `
      <div class="point-config-shell">
        <section class="point-config-hero">
          <div>
            <span class="label-chip">当前点位</span>
            <strong>${escapeHtml(heroName)}</strong>
            <p><code>${escapeHtml(heroCode)}</code> · ${escapeHtml(formatValue(heroAddress))}</p>
          </div>
          <div class="point-detail-hero-meta">
            <span class="pill subtle">${escapeHtml(protocol?.title || device?.protocolType || device?.connectionType || "-")}</span>
            <span class="pill subtle">${escapeHtml(configPoint.readWrite || runtimePoint?.readWrite || "R")}</span>
            <span class="badge ${qualityClass}">${escapeHtml(qualityText)}</span>
            ${dirty ? '<span class="pill point-state-dirty">未保存</span>' : ""}
          </div>
        </section>
        <div class="point-config-grid">
          ${groups.map((group) => `
            <section class="field-group point-config-card">
              <h3>${escapeHtml(group.title)}</h3>
              <div class="point-config-form-grid">
                ${group.fields.map((field) => renderPointConfigField(field, configPoint)).join("")}
              </div>
            </section>`).join("")}
          <section class="field-group point-config-card">
            <h3>运行态</h3>
            <div class="point-runtime-metrics">
              ${renderRuntimeMetric("当前值", runtimePoint?.value, runtimeTone)}
              ${renderRuntimeMetric("原始值", runtimePoint?.rawValue)}
              ${renderRuntimeMetric("质量", qualityText, runtimeTone)}
              ${renderRuntimeMetric("处理耗时", `${runtimePoint?.processingTime ?? "-"} ms`)}
              ${renderRuntimeMetric("单位", configPoint.unit || runtimePoint?.unit || "-")}
              ${renderRuntimeMetric("地址", heroAddress)}
            </div>
          </section>
          <section class="field-group point-config-card point-config-card-wide">
            <h3>高级 JSON</h3>
            <div class="point-config-form-grid point-config-form-grid-wide">
              ${renderPointConfigField({ path: "additionalConfig", label: "扩展配置（additionalConfig JSON）", control: "textarea", valueType: "json-object", rows: 8, fullWidth: true, description: "保留协议扩展字段和上报扩展配置。" }, configPoint)}
              ${renderPointConfigField({ path: "alarmRule", label: "告警规则（alarmRule JSON）", control: "textarea", valueType: "json-string", rows: 6, fullWidth: true, description: "支持数组 JSON；保存时会原样写回告警规则（alarmRule）。" }, configPoint)}
            </div>
          </section>
        </div>
      </div>`;
  }

  function updateSelectedPointHeader(context) {
    const tag = $("#inspectorPointTag");
    if (tag) {
      tag.textContent = context.configPoint?.pointName || context.runtimePoint?.pointName || context.configPoint?.pointCode || context.runtimePoint?.pointCode || "未选择点位";
    }

    if (state.pointConfigLoadingDeviceId && state.pointConfigLoadingDeviceId === context.deviceId) {
      setPointConfigState("加载中");
    } else if (!context.runtimePoint) {
      setPointConfigState("待选择");
    } else if (!context.configPoint) {
      setPointConfigState("仅运行态", "missing");
    } else if (isPointConfigDirty(context.deviceId)) {
      setPointConfigState("有未保存修改", "dirty");
    } else {
      setPointConfigState("已加载", "ready");
    }
  }

  function setFieldError(input, message = "") {
    const label = input?.closest("label");
    const errorNode = label?.querySelector(".field-error");
    input?.classList.toggle("is-invalid", Boolean(message));
    if (errorNode) {
      errorNode.textContent = message;
      errorNode.classList.toggle("hidden", !message);
    }
  }

  function coercePointEditorValue(value, valueType) {
    const text = String(value ?? "");
    switch (valueType) {
      case "number":
        if (!text.trim()) return null;
        if (Number.isNaN(Number(text))) throw new Error("请输入数值");
        return Number(text);
      case "integer":
        if (!text.trim()) return null;
        if (Number.isNaN(Number.parseInt(text, 10))) throw new Error("请输入整数");
        return Number.parseInt(text, 10);
      case "boolean":
        if (!text.trim()) return null;
        return text === "true";
      case "json-object": {
        if (!text.trim()) return {};
        const parsed = JSON.parse(text);
        if (!isPlainObject(parsed)) throw new Error("请输入 JSON 对象");
        return parsed;
      }
      case "json-string":
        if (!text.trim()) return "";
        return JSON.stringify(JSON.parse(text));
      default:
        return text;
    }
  }

  function handleSelectedPointConfigInput(event) {
    const input = event.target.closest("[data-point-field]");
    if (!input) {
      return;
    }
    const context = selectedPointConfigContext();
    if (!context.configPoint) {
      return;
    }

    try {
      setPath(context.configPoint, input.dataset.pointField, coercePointEditorValue(input.value, input.dataset.valueType || "string"));
      setFieldError(input, "");
      setPointConfigDirty(context.deviceId, true);
      updateSelectedPointHeader(context);
      syncPointConfigActions({ canSave: true, canReload: Boolean(context.deviceId) });
    } catch (error) {
      setFieldError(input, error.message || "字段值无效");
      setPointConfigDirty(context.deviceId, true);
      updateSelectedPointHeader(context);
    }
  }

  function sanitizePointConfigForSave(point) {
    const next = cloneJson(point) || {};
    delete next.__configKey;
    next.additionalConfig = isPlainObject(next.additionalConfig)
        ? next.additionalConfig
        : {};
    if (!hasValue(next.alarmRule)) {
      next.alarmRule = "";
    } else if (typeof next.alarmRule !== "string") {
      next.alarmRule = JSON.stringify(next.alarmRule);
    } else {
      try {
        next.alarmRule = JSON.stringify(JSON.parse(next.alarmRule));
      } catch (error) {
        throw new Error(
            `点位 ${next.pointName || next.pointCode || ""} 的告警规则（alarmRule JSON）格式错误`
        );
      }
    }
    return next;
  }

  async function saveSelectedPointConfig() {
    const context = selectedPointConfigContext();
    if (!context.deviceId || !context.configPoint || !context.configs.length) {
      toast("当前没有可保存的点位配置", true);
      return;
    }
    if ($("#selectedPointPanel")?.querySelector(".field-error:not(.hidden)")) {
      toast("请先修正右侧表单中的无效字段", true);
      return;
    }
    if (!hasValue(context.configPoint.pointName) || !hasValue(context.configPoint.pointCode) || !hasValue(context.configPoint.address)) {
      toast("点位名称、标识符、寄存器地址不能为空", true);
      return;
    }

    syncPointConfigActions({ canSave: true, canReload: true, saving: true });
    await callApi(`/api/config/device/${encodeURIComponent(context.deviceId)}/points`, {
      method: "PUT",
      body: JSON.stringify(context.configs.map((point) => sanitizePointConfigForSave(point)))
    });
    setPointConfigDirty(context.deviceId, false);
    await Promise.all([loadPointConfigs(context.deviceId, true), loadRealtime()]);
    toast(`点位配置已保存：${context.configPoint.pointName || context.configPoint.pointCode || "当前点位"}`);
  }

  async function reloadSelectedPointConfig() {
    const deviceId = selectedDeviceId();
    if (!deviceId) {
      toast("请选择设备", true);
      return;
    }
    await loadPointConfigs(deviceId, true);
    toast("已重新加载当前设备点位配置");
  }

  const originalBindEvents = bindEvents;
  bindEvents = function bindEventsWithPointConfig() {
    originalBindEvents();
    $("#saveSelectedPointBtn")?.addEventListener("click", () => saveSelectedPointConfig().catch((error) => toast(error.message, true)));
    $("#reloadSelectedPointBtn")?.addEventListener("click", () => reloadSelectedPointConfig().catch((error) => toast(error.message, true)));
  };

  const originalBindConsoleShell = bindConsoleShell;
  bindConsoleShell = function bindConsoleShellWithPointConfig() {
    originalBindConsoleShell();
    $("#realtimeDeviceSelect")?.addEventListener("change", () => {
      loadPointConfigs($("#realtimeDeviceSelect")?.value).catch((error) => toast(error.message, true));
    });
    const selectedPointPanel = $("#selectedPointPanel");
    if (selectedPointPanel) {
      selectedPointPanel.addEventListener("input", handleSelectedPointConfigInput);
      selectedPointPanel.addEventListener("change", handleSelectedPointConfigInput);
    }
  };

  const originalSelectDevice = selectDevice;
  selectDevice = function selectDeviceWithPointConfig(deviceId) {
    originalSelectDevice(deviceId);
    loadPointConfigs(deviceId).catch((error) => toast(error.message, true));
  };

  const originalFillDeviceSelects = fillDeviceSelects;
  fillDeviceSelects = function fillDeviceSelectsWithPointConfig() {
    originalFillDeviceSelects();
    if (state.devices.length) {
      loadPointConfigs(selectedDeviceId()).catch((error) => toast(error.message, true));
    }
  };

  clearSelectedPointInspector = function clearSelectedPointInspectorEnhanced() {
    state.selectedRealtimePointKey = null;
    state.realtimePoints = [];
    state.pointConfigRenderedKey = null;
    $("#selectedPointEmpty")?.classList.remove("hidden");
    $("#selectedPointPanel")?.classList.add("hidden");
    const tag = $("#inspectorPointTag");
    if (tag) {
      tag.textContent = "未选择点位";
    }
    setPointConfigState("待选择");
    syncPointConfigActions({ canSave: false, canReload: Boolean(selectedDeviceId()) });
    document.querySelectorAll("#realtimeRows tr[data-point-key]").forEach((row) => row.classList.remove("is-selected"));
  };

  renderSelectedPointInspector = function renderSelectedPointInspectorEnhanced() {
    const context = selectedPointConfigContext();
    const empty = $("#selectedPointEmpty");
    const panel = $("#selectedPointPanel");
    const workspace = $("#pointConfigWorkspace");
    const samePoint = state.pointConfigRenderedKey && state.pointConfigRenderedKey === state.selectedRealtimePointKey;
    const editingCurrentPoint = panel?.contains(document.activeElement) && samePoint && isPointConfigDirty(context.deviceId);

    updateSelectedPointHeader(context);
    if (!context.runtimePoint) {
      empty?.classList.remove("hidden");
      panel?.classList.add("hidden");
      syncPointConfigActions({ canSave: false, canReload: Boolean(context.deviceId) });
      return;
    }

    if (context.deviceId && !hasOwn(state.devicePointConfigs, context.deviceId) && state.pointConfigLoadingDeviceId !== context.deviceId) {
      loadPointConfigs(context.deviceId).catch((error) => toast(error.message, true));
    }

    if (state.pointConfigLoadingDeviceId === context.deviceId && !context.configPoint) {
      if (empty) {
        empty.innerHTML = "<strong>正在加载点位配置</strong><span>正在从配置治理接口读取当前设备的点位列表，稍后会切换成可编辑表单。</span>";
        empty.classList.remove("hidden");
      }
      panel?.classList.add("hidden");
      syncPointConfigActions({ canSave: false, canReload: false });
      return;
    }

    if (editingCurrentPoint) {
      syncPointConfigActions({ canSave: Boolean(context.configPoint), canReload: Boolean(context.deviceId) });
      return;
    }

    if (empty) {
      empty.innerHTML = "<strong>暂无选中的点位</strong><span>先在中间点位表里选择一个点位，这里会切换成可编辑的点位配置工作区。</span>";
      empty.classList.add("hidden");
    }
    panel?.classList.remove("hidden");
    if (workspace) {
      workspace.innerHTML = renderSelectedPointWorkspace(context);
    }
    state.pointConfigRenderedKey = state.selectedRealtimePointKey || null;
    syncPointConfigActions({ canSave: Boolean(context.configPoint), canReload: Boolean(context.deviceId) });
  };
})();
