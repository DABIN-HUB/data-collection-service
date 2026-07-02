(function () {
  const DATA_TYPES = ["BOOLEAN", "BYTE", "SHORT", "INT", "LONG", "FLOAT", "DOUBLE", "STRING", "UINT16", "UINT32"];
  const STATUS_OPTIONS = [
    { value: 1, label: "启用" },
    { value: 0, label: "禁用" },
    { value: 2, label: "维护中" },
    { value: 3, label: "异常" }
  ];
  const READ_WRITE_OPTIONS = [
    { value: "R", label: "R / 只读" },
    { value: "W", label: "W / 只写" },
    { value: "RW", label: "RW / 读写" }
  ];
  const COLLECTION_MODE_OPTIONS = [
    { value: "POLLING", label: "POLLING / 轮询" },
    { value: "SUBSCRIPTION", label: "SUBSCRIPTION / 订阅" },
    { value: "EVENT", label: "EVENT / 事件" }
  ];
  const BOOLEAN_OPTIONS = [
    { value: true, label: "是" },
    { value: false, label: "否" }
  ];
  const ENABLE_OPTIONS = [
    { value: 1, label: "启用" },
    { value: 0, label: "关闭" }
  ];
  const MODBUS_FIELDS = [
    { path: "additionalConfig.registerType", label: "Register Type（寄存器区，当前以后端 address 判定）", control: "select", valueType: "string", allowEmpty: false, options: ["HOLDING_REGISTER", "INPUT_REGISTER", "COIL", "DISCRETE_INPUT"].map((value) => ({ value, label: value })) },
    { path: "additionalConfig.byteOrder", label: "Byte Order（字节序，当前实际取连接配置）", control: "select", valueType: "string", options: ["BIG_ENDIAN", "LITTLE_ENDIAN"].map((value) => ({ value, label: value })) },
    { path: "additionalConfig.wordOrder", label: "Word Order（字序，当前后端未使用）", control: "select", valueType: "string", options: ["BIG_ENDIAN", "LITTLE_ENDIAN"].map((value) => ({ value, label: value })) },
    { path: "additionalConfig.bitIndex", label: "Bit Index（位偏移，当前后端未使用）", control: "number", valueType: "integer", step: "1" },
    { path: "additionalConfig.functionCode", label: "Function Code（功能码，当前自动按寄存器区选择）", control: "number", valueType: "integer", step: "1" },
    { path: "additionalConfig.stringLength", label: "String Length（字符串长度，当前 Modbus 未使用）", control: "number", valueType: "integer", step: "1" }
  ];
  const PROTOCOL_FIELDS = {
    MODBUS_TCP: MODBUS_FIELDS,
    MODBUS_RTU: MODBUS_FIELDS,
    MQTT: [
      { path: "additionalConfig.topic", label: "Subscribe Topic", control: "text", valueType: "string" },
      { path: "additionalConfig.writeTopic", label: "Write Topic", control: "text", valueType: "string" },
      { path: "additionalConfig.qos", label: "QoS", control: "select", valueType: "integer", options: [0, 1, 2].map((value) => ({ value, label: String(value) })) },
      { path: "additionalConfig.retain", label: "Retain", control: "select", valueType: "boolean", options: BOOLEAN_OPTIONS },
      { path: "additionalConfig.jsonPath", label: "JSONPath", control: "text", valueType: "string" },
      { path: "additionalConfig.payloadEncoding", label: "Payload Encoding", control: "select", valueType: "string", options: ["JSON", "PLAIN_TEXT", "BASE64", "HEX"].map((value) => ({ value, label: value })) },
      { path: "additionalConfig.charset", label: "Charset", control: "text", valueType: "string", placeholder: "UTF-8" },
      { path: "additionalConfig.publishTemplate", label: "Publish Template", control: "textarea", valueType: "string", rows: 3, fullWidth: true }
    ],
    OPC_UA: [
      { path: "additionalConfig.nodeId", label: "NodeId", control: "text", valueType: "string" },
      { path: "additionalConfig.namespace", label: "Namespace", control: "number", valueType: "integer", step: "1" },
      { path: "additionalConfig.identifier", label: "Identifier", control: "text", valueType: "string" },
      { path: "additionalConfig.identifierType", label: "Identifier Type", control: "select", valueType: "string", options: ["STRING", "NUMERIC", "GUID", "OPAQUE"].map((value) => ({ value, label: value })) },
      { path: "additionalConfig.opcUaType", label: "OPC UA Type", control: "text", valueType: "string" },
      { path: "additionalConfig.samplingInterval", label: "Sampling Interval (ms)", control: "number", valueType: "integer", step: "1" },
      { path: "additionalConfig.publishingInterval", label: "Publishing Interval (ms)", control: "number", valueType: "integer", step: "1" },
      { path: "additionalConfig.queueSize", label: "Queue Size", control: "number", valueType: "integer", step: "1" },
      { path: "additionalConfig.subscribe", label: "Subscribe", control: "select", valueType: "boolean", options: BOOLEAN_OPTIONS },
      { path: "additionalConfig.monitor", label: "Monitor", control: "select", valueType: "boolean", options: BOOLEAN_OPTIONS }
    ],
    OPC_DA: [
      { path: "additionalConfig.itemId", label: "Item ID", control: "text", valueType: "string" },
      { path: "additionalConfig.itemPath", label: "Item Path", control: "text", valueType: "string" },
      { path: "additionalConfig.dataSource", label: "Data Source", control: "select", valueType: "string", options: ["DEVICE", "CACHE"].map((value) => ({ value, label: value })) }
    ],
    IEC104: [
      { path: "additionalConfig.typeId", label: "Type ID", control: "number", valueType: "integer", step: "1" },
      { path: "additionalConfig.iecTypeId", label: "IEC Type ID", control: "number", valueType: "integer", step: "1" },
      { path: "additionalConfig.registerType", label: "Register Type", control: "text", valueType: "string" },
      { path: "additionalConfig.writeAddress", label: "Write Address", control: "text", valueType: "string" },
      { path: "additionalConfig.writeCommonAddress", label: "Write Common Address", control: "number", valueType: "integer", step: "1" },
      { path: "additionalConfig.writeQl", label: "Write QL", control: "number", valueType: "integer", step: "1" },
      { path: "additionalConfig.writeSelect", label: "Write Select", control: "select", valueType: "boolean", options: BOOLEAN_OPTIONS }
    ],
    KNX: [
      { path: "additionalConfig.dptId", label: "DPT ID", control: "text", valueType: "string" },
      { path: "additionalConfig.dpt", label: "DPT", control: "text", valueType: "string" }
    ]
  };

  function installLocalPointEditor() {
    if (installLocalPointEditor.installed) {
      return;
    }
    installLocalPointEditor.installed = true;
    state.localPoints = [];
    state.selectedLocalPointIndex = -1;
    state.localPointSearch = "";

    window.openLocalDeviceForm = openLocalDeviceForm;
    window.closeLocalDeviceForm = closeLocalDeviceForm;
    window.renderLocalProtocolSelection = renderLocalProtocolSelection;
    window.formatLocalPointsJson = formatLocalPointsJson;
    window.buildLocalDeviceRequest = buildLocalDeviceRequest;
    window.saveLocalDevice = saveLocalDevice;
    window.editLocalDevice = editLocalDeviceOverride;

    intercept("#openLocalDeviceBtn", "click", () => openLocalDeviceForm());
    intercept("#cancelLocalDeviceBtn", "click", closeLocalDeviceForm);
    intercept("#saveLocalDeviceBtn", "click", saveLocalDevice);
    intercept("#localProtocolSelect", "change", renderLocalProtocolSelection);
    intercept("#formatLocalPointsBtn", "click", formatLocalPointsJson);
    bind("#localEditorBackdrop", "click", closeLocalDeviceForm);

    bind("#applyLocalPointsJsonBtn", "click", applyLocalPointsJson);
    bind("#addLocalPointBtn", "click", addLocalPoint);
    bind("#duplicateLocalPointBtn", "click", duplicateLocalPoint);
    bind("#deleteLocalPointBtn", "click", removeLocalPoint);
    bind("#localPointSearch", "input", (event) => {
      state.localPointSearch = event.target.value || "";
      renderLocalPointList();
    });
    bind("#localPointRows", "click", handleLocalPointListClick);
    bind("#localPointDetail", "input", handleLocalPointDetailInput);
    bind("#localPointDetail", "change", handleLocalPointDetailInput);
    bind("#localPointDetail", "click", handleLocalPointDetailClick);
    document.addEventListener("keydown", handleLocalEditorKeydown);
  }

  function bind(selector, eventName, handler, useCapture = false) {
    const element = $(selector);
    if (element) {
      element.addEventListener(eventName, handler, useCapture);
    }
  }

  function intercept(selector, eventName, handler) {
    bind(selector, eventName, (event) => {
      event.preventDefault();
      event.stopImmediatePropagation();
      handler(event);
    }, true);
  }

  function openLocalDeviceForm(bundle = null) {
    state.localDeviceEditingId = bundle?.device?.id || bundle?.device?.deviceId || null;
    state.localPointSearch = "";
    $("#localDevicePanel").classList.remove("hidden");
    $("#localEditorBackdrop")?.classList.remove("hidden");
    document.body.classList.add("modal-active");
    document.querySelector(".local-editor-placeholder")?.classList.add("hidden");

    const device = bundle?.device || {};
    const connection = bundle?.connection || {};
    const deviceId = device.id || device.deviceId || "";
    const protocol = canonicalProtocolForUi(device.protocolType || connection.connectionType || $("#localProtocolSelect").value || "MODBUS_TCP");
    const points = bundle?.points || [defaultPointTemplate(deviceId || "local-device", protocol, { pointCode: createUniqueCode([], "point"), pointName: "点位 1" })];
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
    $("#localPointSearch").value = "";

    state.localPoints = normalizeLocalPoints(points, deviceId || "local-device", protocol);
    state.selectedLocalPointIndex = state.localPoints.length ? 0 : -1;

    renderLocalProtocolSelection();
    fillProtocolForm("#localConnectionForm", state.currentLocalProtocol, {
      ...connection,
      host: connection.host || device.ipAddress,
      port: connection.port || device.port,
      connectionType: connection.connectionType || protocol
    });
    renderLocalPointEditor();
  }

  function closeLocalDeviceForm() {
    state.localDeviceEditingId = null;
    state.localPoints = [];
    state.selectedLocalPointIndex = -1;
    state.localPointSearch = "";
    state.currentLocalProtocol = null;
    $("#localDevicePanel").classList.add("hidden");
    $("#localEditorBackdrop")?.classList.add("hidden");
    document.body.classList.remove("modal-active");
    document.querySelector(".local-editor-placeholder")?.classList.remove("hidden");
    $("#localDeviceId").disabled = false;
    $("#localPointSearch").value = "";
    $("#localPointRows").innerHTML = `<tr><td colspan="5">暂无点位</td></tr>`;
    $("#localPointDetail").innerHTML = "";
    $("#localPointEmpty").classList.remove("hidden");
    refreshLocalEditorSummary();
  }

  function refreshLocalEditorSummary() {
    const protocolCode = canonicalProtocolForUi($("#localProtocolSelect")?.value || "");
    const protocolLabel = state.currentLocalProtocol?.title || protocolCode || "-";
    const pointCount = Array.isArray(state.localPoints) ? state.localPoints.length : 0;
    if ($("#localEditorProtocolText")) {
      $("#localEditorProtocolText").textContent = protocolLabel;
    }
    if ($("#localEditorPointCount")) {
      $("#localEditorPointCount").textContent = String(pointCount);
    }
  }

  function handleLocalEditorKeydown(event) {
    if (event.key !== "Escape") {
      return;
    }
    const panel = $("#localDevicePanel");
    if (panel && !panel.classList.contains("hidden")) {
      closeLocalDeviceForm();
    }
  }

  function renderLocalProtocolSelection() {
    const protocolCode = canonicalProtocolForUi($("#localProtocolSelect").value || "MODBUS_TCP");
    $("#localProtocolSelect").value = protocolCode;
    state.currentLocalProtocol = getProtocolSchema(protocolCode);
    updateProtocolMetaHelp("#localProtocolMetaHelp", state.currentLocalProtocol, `${state.currentLocalProtocol?.title || protocolCode} 协议说明`);
    renderProtocolForm("#localConnectionForm", state.currentLocalProtocol, "localConnectionForm");
    renderLocalPointList();
    renderLocalPointDetail();
    syncLocalPointsJson();
    refreshLocalEditorSummary();
  }

  function defaultPointTemplate(deviceId, protocolCode, seed = {}) {
    const draft = isPlainObject(seed) ? cloneData(seed) : {};
    const pointCode = draft.pointCode || "point";
    const address = draft.address || defaultPointAddress(protocolCode);
    const additionalConfig = { ...(isPlainObject(draft.additionalConfig) ? draft.additionalConfig : {}) };
    if (additionalConfig.reportEnabled === undefined) {
      additionalConfig.reportEnabled = true;
    }
    if (!hasValue(additionalConfig.reportField)) {
      additionalConfig.reportField = pointCode;
    }
    if (protocolCode === "MQTT" && !hasValue(additionalConfig.topic)) {
      additionalConfig.topic = address;
    }
    if (protocolCode === "OPC_UA" && !hasValue(additionalConfig.nodeId)) {
      additionalConfig.nodeId = address;
    }
    return {
      ...draft,
      pointCode,
      pointName: draft.pointName || pointCode,
      deviceId: draft.deviceId || deviceId,
      address,
      dataType: draft.dataType || defaultPointDataType(protocolCode),
      readWrite: draft.readWrite || "R",
      collectionMode: draft.collectionMode || (protocolCode === "MQTT" ? "SUBSCRIPTION" : "POLLING"),
      status: draft.status ?? 1,
      cacheEnabled: draft.cacheEnabled ?? 0,
      alarmEnabled: draft.alarmEnabled ?? 0,
      baseCollectionInterval: draft.baseCollectionInterval ?? adaptiveDefaults.baseCollectionInterval,
      currentCollectionInterval: draft.currentCollectionInterval ?? adaptiveDefaults.baseCollectionInterval,
      minCollectionInterval: draft.minCollectionInterval ?? adaptiveDefaults.minCollectionInterval,
      maxCollectionInterval: draft.maxCollectionInterval ?? adaptiveDefaults.maxCollectionInterval,
      pointChangeThreshold: draft.pointChangeThreshold ?? adaptiveDefaults.pointChangeThreshold,
      additionalConfig
    };
  }

  function defaultPointAddress(protocolCode) {
    switch (canonicalProtocolForUi(protocolCode)) {
      case "MQTT": return "sensor/temperature";
      case "OPC_UA": return "ns=2;s=Channel1.Device1.Tag1";
      case "SIEMENS_S7": return "DB1.DBW0";
      case "IEC104": return "1";
      case "KNX":
      case "KNXNET_IP": return "1/0/1";
      default: return "40001";
    }
  }

  function normalizeLocalPoints(points, deviceId, protocolCode) {
    const list = Array.isArray(points) ? points : points ? [points] : [];
    return list.map((point, index) => normalizeLocalPoint(point, deviceId, protocolCode, index));
  }

  function normalizeLocalPoint(point, deviceId, protocolCode, index) {
    const draft = isPlainObject(point) ? cloneData(point) : {};
    const additionalConfig = isPlainObject(draft.additionalConfig) ? draft.additionalConfig : {};
    const normalized = {
      ...draft,
      pointCode: hasValue(draft.pointCode) ? String(draft.pointCode).trim() : `point_${index + 1}`,
      pointName: hasValue(draft.pointName) ? String(draft.pointName).trim() : `点位 ${index + 1}`,
      deviceId: draft.deviceId || deviceId || "",
      address: hasValue(draft.address) ? String(draft.address).trim() : "",
      dataType: draft.dataType || defaultPointDataType(protocolCode),
      readWrite: draft.readWrite || "R",
      collectionMode: draft.collectionMode || (protocolCode === "MQTT" ? "SUBSCRIPTION" : "POLLING"),
      status: draft.status ?? 1,
      cacheEnabled: draft.cacheEnabled ?? 0,
      alarmEnabled: draft.alarmEnabled ?? 0,
      additionalConfig
    };
    if (!normalized.address && protocolCode === "MQTT" && hasValue(additionalConfig.topic)) {
      normalized.address = String(additionalConfig.topic).trim();
    }
    if (!normalized.address && protocolCode === "OPC_UA" && hasValue(additionalConfig.nodeId)) {
      normalized.address = String(additionalConfig.nodeId).trim();
    }
    return normalized;
  }

  function resolveAdaptiveDefaults(device, points) {
    const firstPoint = Array.isArray(points) && points.length ? points[0] : {};
    const base = positiveNumber(firstPoint.baseCollectionInterval) || positiveNumber(device.collectionInterval) || adaptiveDefaults.baseCollectionInterval;
    return {
      baseCollectionInterval: base,
      minCollectionInterval: positiveNumber(firstPoint.minCollectionInterval) || adaptiveDefaults.minCollectionInterval,
      maxCollectionInterval: positiveNumber(firstPoint.maxCollectionInterval) || adaptiveDefaults.maxCollectionInterval,
      pointChangeThreshold: positiveNumber(firstPoint.pointChangeThreshold) || adaptiveDefaults.pointChangeThreshold
    };
  }

  function readAdaptiveFormValues() {
    const min = positiveNumber($("#localMinCollectionInterval").value) || adaptiveDefaults.minCollectionInterval;
    const max = positiveNumber($("#localMaxCollectionInterval").value) || adaptiveDefaults.maxCollectionInterval;
    const normalizedMin = Math.min(min, max);
    const normalizedMax = Math.max(min, max);
    const base = positiveNumber($("#localCollectionInterval").value) || adaptiveDefaults.baseCollectionInterval;
    return {
      baseCollectionInterval: Math.max(normalizedMin, Math.min(base, normalizedMax)),
      minCollectionInterval: normalizedMin,
      maxCollectionInterval: normalizedMax,
      pointChangeThreshold: positiveNumber($("#localPointChangeThreshold").value) || adaptiveDefaults.pointChangeThreshold
    };
  }

  function ensureSelection() {
    if (!Array.isArray(state.localPoints) || !state.localPoints.length) {
      state.selectedLocalPointIndex = -1;
      return;
    }
    if (!Number.isInteger(state.selectedLocalPointIndex) || state.selectedLocalPointIndex < 0 || state.selectedLocalPointIndex >= state.localPoints.length) {
      state.selectedLocalPointIndex = 0;
    }
  }

  function selectedPoint() {
    ensureSelection();
    return state.selectedLocalPointIndex >= 0 ? state.localPoints[state.selectedLocalPointIndex] || null : null;
  }

  function renderLocalPointEditor() {
    ensureSelection();
    renderLocalPointList();
    renderLocalPointDetail();
    syncLocalPointsJson();
    refreshLocalEditorSummary();
  }

  function renderLocalPointList() {
    const target = $("#localPointRows");
    if (!target) {
      return;
    }
    const search = String(state.localPointSearch || "").trim().toLowerCase();
    const rows = (state.localPoints || []).map((point, index) => ({ point, index }))
      .filter(({ point }) => !search || pointMatches(point, search));
    const active = selectedPoint();
    $("#localPointCount").textContent = `${(state.localPoints || []).length} 个点位`;
    const parts = [];
    if (active) {
      parts.push(`当前：${displayPointName(active, state.selectedLocalPointIndex)}`);
    }
    if (search) {
      parts.push(`筛选 ${rows.length}/${state.localPoints.length}`);
    }
    $("#localPointSelectionMeta").textContent = parts.join(" | ") || "未选择点位";
    $("#duplicateLocalPointBtn").disabled = !active;
    $("#deleteLocalPointBtn").disabled = !active;
    target.innerHTML = rows.length ? rows.map(({ point, index }) => `
      <tr class="${index === state.selectedLocalPointIndex ? "is-selected" : ""}">
        <td>
          <button type="button" class="point-select-button" data-select-local-point="${index}">
            <strong>${escapeHtml(displayPointName(point, index))}</strong>
            <span>${escapeHtml(point.pointCode || `point_${index + 1}`)}</span>
          </button>
        </td>
        <td>${escapeHtml(resolvePointAddress(point) || "-")}</td>
        <td>${escapeHtml(resolvePointTypeSummary(point, canonicalProtocolForUi($("#localProtocolSelect").value || "MODBUS_TCP")))}</td>
        <td>${escapeHtml(point.readWrite || "-")}</td>
        <td>${escapeHtml(statusLabel(point.status))}</td>
      </tr>`).join("") : `<tr><td colspan="5">${search ? "没有匹配的点位" : "暂无点位"}</td></tr>`;
  }

  function renderLocalPointDetail() {
    const target = $("#localPointDetail");
    const empty = $("#localPointEmpty");
    const point = selectedPoint();
    if (!target || !empty) {
      return;
    }
    if (!point) {
      empty.classList.remove("hidden");
      target.innerHTML = "";
      return;
    }
    empty.classList.add("hidden");
    const protocolCode = canonicalProtocolForUi($("#localProtocolSelect").value || "MODBUS_TCP");
    const protocol = state.currentLocalProtocol || getProtocolSchema(protocolCode);
    const basicFields = [
      { path: "pointCode", label: "点位编码", control: "text", valueType: "string", required: true, placeholder: "temperature", listRefresh: true },
      { path: "pointName", label: "点位名称", control: "text", valueType: "string", required: true, placeholder: "温度", listRefresh: true },
      { path: "pointAlias", label: "点位别名", control: "text", valueType: "string" },
      { path: "address", label: "点位地址", control: "text", valueType: "string", required: true, placeholder: defaultPointAddress(protocolCode), listRefresh: true, helpHtml: protocolCode === "SIEMENS_S7" ? s7AddressHelpTooltipHtml() : "", helpLabel: "S7 地址说明" },
      { path: "groupId", label: "分组 ID", control: "text", valueType: "string" },
      ...buildTypeEditorFields(protocolCode, protocol),
      { path: "readWrite", label: "读写权限", control: "select", valueType: "string", allowEmpty: false, options: READ_WRITE_OPTIONS, listRefresh: true },
      { path: "collectionMode", label: "采集模式", control: "select", valueType: "string", allowEmpty: false, options: COLLECTION_MODE_OPTIONS },
      { path: "status", label: "状态", control: "select", valueType: "integer", allowEmpty: false, options: STATUS_OPTIONS, listRefresh: true },
      { path: "remark", label: "备注", control: "textarea", valueType: "string", rows: 3, fullWidth: true }
    ];
    const dataFields = [
      { path: "unit", label: "单位", control: "text", valueType: "string", placeholder: "°C" },
      { path: "additionalConfig.sourceUnit", label: "sourceUnit", control: "text", valueType: "string" },
      { path: "scalingFactor", label: "缩放系数", control: "number", valueType: "number", step: "0.0001" },
      { path: "offset", label: "偏移量", control: "number", valueType: "number", step: "0.0001" },
      { path: "deadband", label: "死区", control: "number", valueType: "number", step: "0.0001" },
      { path: "minValue", label: "最小值", control: "number", valueType: "number", step: "0.0001" },
      { path: "maxValue", label: "最大值", control: "number", valueType: "number", step: "0.0001" },
      { path: "precision", label: "小数位", control: "number", valueType: "integer", step: "1", min: "0" },
      { path: "unitId", label: "Unit ID", control: "number", valueType: "integer", step: "1" },
      { path: "commonAddress", label: "Common Address", control: "number", valueType: "integer", step: "1" }
    ];
    const reportFields = [
      { path: "priority", label: "优先级", control: "number", valueType: "integer", step: "1" },
      { path: "cacheEnabled", label: "启用缓存", control: "select", valueType: "integer", allowEmpty: false, options: ENABLE_OPTIONS },
      { path: "cacheDuration", label: "缓存时长(秒)", control: "number", valueType: "integer", step: "1" },
      { path: "additionalConfig.reportEnabled", label: "参与设备上报", control: "select", valueType: "boolean", allowEmpty: false, options: BOOLEAN_OPTIONS },
      { path: "additionalConfig.reportField", label: "reportField", control: "text", valueType: "string" },
      { path: "additionalConfig.changeThreshold", label: "变化阈值", control: "number", valueType: "number", step: "0.0001" },
      { path: "additionalConfig.changeMinIntervalMs", label: "变化最小间隔(ms)", control: "number", valueType: "integer", step: "1" },
      { path: "additionalConfig.eventEnabled", label: "事件上报", control: "select", valueType: "boolean", allowEmpty: false, options: BOOLEAN_OPTIONS },
      { path: "additionalConfig.eventMinIntervalMs", label: "事件最小间隔(ms)", control: "number", valueType: "integer", step: "1" },
      { path: "additionalConfig.historyEnabled", label: "写历史存储", control: "select", valueType: "boolean", allowEmpty: false, options: BOOLEAN_OPTIONS },
      { path: "additionalConfig.streamEnabled", label: "写 Redis Stream", control: "select", valueType: "boolean", allowEmpty: false, options: BOOLEAN_OPTIONS }
    ];
    const alarmFields = [
      { path: "alarmEnabled", label: "启用告警", control: "select", valueType: "integer", allowEmpty: false, options: ENABLE_OPTIONS }
    ];
    const readonlyItems = [
      ["id", point.id],
      ["pointId", point.pointId],
      ["deviceId", point.deviceId],
      ["deviceName", point.deviceName],
      ["baseCollectionInterval", point.baseCollectionInterval],
      ["currentCollectionInterval", point.currentCollectionInterval],
      ["minCollectionInterval", point.minCollectionInterval],
      ["maxCollectionInterval", point.maxCollectionInterval],
      ["pointChangeThreshold", point.pointChangeThreshold],
      ["stableCount", point.stableCount],
      ["lastValue", point.lastValue],
      ["changeRate", point.changeRate],
      ["lastAdjustTime", point.lastAdjustTime ? formatTs(point.lastAdjustTime) : null],
      ["reportFieldConflict", point.reportFieldConflict === undefined ? null : String(Boolean(point.reportFieldConflict))],
      ["createTime", point.createTime ? formatTs(point.createTime) : null],
      ["updateTime", point.updateTime ? formatTs(point.updateTime) : null]
    ].filter((item) => hasValue(item[1]));
    target.innerHTML = `
      <div class="point-detail-stack">
        <section class="point-detail-hero">
          <div>
            <span class="label-chip">当前点位</span>
            <strong>${escapeHtml(displayPointName(point, state.selectedLocalPointIndex))}</strong>
            <p>${escapeHtml(point.pointCode || "-")} · ${escapeHtml(resolvePointAddress(point) || "未设置地址")}</p>
          </div>
          <div class="point-detail-hero-meta">
            <span class="pill subtle">${escapeHtml(resolvePointTypeSummary(point, protocolCode))}</span>
            <span class="pill subtle">${escapeHtml(point.readWrite || "-")}</span>
            <span class="pill subtle">${escapeHtml(statusLabel(point.status))}</span>
          </div>
        </section>
        <div class="point-detail-grid">
          <section class="field-group">
            <h3>基础信息</h3>
            <p class="point-section-note">设备级 Base / Min / Max CollectionInterval 与 PointChangeThreshold 会在保存时统一回写到全部点位。</p>
            <div class="form-grid">${renderFields(basicFields, point)}</div>
          </section>
          <section class="field-group">
            <h3>数据处理</h3>
            <div class="form-grid">${renderFields(dataFields, point)}</div>
          </section>
          <section class="field-group">
            <h3>上报 / 缓存</h3>
            <div class="form-grid">${renderFields(reportFields, point)}</div>
            ${renderReportBindings(point)}
          </section>
          <section class="field-group">
            <h3>${renderProtocolSectionTitle(protocolCode)}</h3>
            ${renderProtocolFields(protocolCode, point)}
          </section>
          <section class="field-group field-group-wide">
            <h3>告警规则</h3>
            <div class="form-grid">${renderFields(alarmFields, point)}</div>
            ${renderAlarmRules(point)}
          </section>
          <section class="field-group field-group-wide">
            <h3>只读信息</h3>
            ${readonlyItems.length ? `<div class="readonly-grid">${readonlyItems.map(([label, value]) => renderReadonly(label, value)).join("")}</div>` : `<p class="field-description">当前点位没有额外只读运行态信息。</p>`}
          </section>
        </div>
      </div>`;
  }

  function renderFields(fields, point) {
    return fields.map((field) => renderFieldControl(field, point)).join("");
  }

  function s7AddressHelpTooltipHtml() {
    return `
      <div class="field-help-card">
        <p class="field-help-title">S7 点位地址速查</p>
        <p>地址栏支持简写和完整 PLC4X 地址。简写会由后端自动补成 PLC4X 可用地址。</p>
        <p class="field-help-subtitle">推荐写法</p>
        <ul class="field-help-list">
          <li><code>DB1.DBX0.0</code> -> <code>%DB1:0.0:BOOL</code></li>
          <li><code>DB1.DBB2</code> -> 默认补成 <code>%DB1:2:BYTE</code>，也可配合 <code>S7 driver type=SINT</code></li>
          <li><code>DB1.DBW0</code> -> 默认补成 <code>%DB1:0:INT</code>，也可配合 <code>S7 driver type=UINT</code></li>
          <li><code>DB1.DBD4</code> + <code>S7 driver type=REAL</code> -> <code>%DB1:4:REAL</code></li>
          <li><code>I0.0</code> / <code>Q0.0</code> / <code>M10.0</code> -> <code>%I0.0:BOOL</code> / <code>%Q0.0:BOOL</code> / <code>%M10.0:BOOL</code></li>
        </ul>
        <p class="field-help-subtitle">位地址：DBX / I / Q / M</p>
        <ul class="field-help-list">
          <li>只表示 <code>BOOL</code></li>
          <li>取值范围：<code>true</code>（1）或 <code>false</code>（0）</li>
          <li>示例：<code>DB1.DBX0.0</code>、<code>I0.0</code>、<code>Q0.0</code>、<code>M10.0</code></li>
        </ul>
        <p class="field-help-subtitle">1 字节：DBB / IB / QB / MB</p>
        <ul class="field-help-list">
          <li>常见类型：<code>BYTE</code>、<code>USINT</code>、<code>SINT</code>、<code>CHAR</code></li>
          <li><code>BYTE/USINT</code> 范围：<code>0 ~ 255</code></li>
          <li><code>SINT</code> 范围：<code>-128 ~ 127</code></li>
          <li>简写不写类型时，默认按 <code>BYTE</code> 处理</li>
        </ul>
        <p class="field-help-subtitle">2 字节：DBW / IW / QW / MW</p>
        <ul class="field-help-list">
          <li>常见类型：<code>INT</code>、<code>UINT</code>、<code>WORD</code></li>
          <li><code>INT</code> 范围：<code>-32768 ~ 32767</code></li>
          <li><code>UINT/WORD</code> 范围：<code>0 ~ 65535</code></li>
          <li>简写不写类型时，默认按 <code>INT</code> 处理</li>
        </ul>
        <p class="field-help-subtitle">4 字节：DBD / ID / QD / MD</p>
        <ul class="field-help-list">
          <li>常见类型：<code>DINT</code>、<code>UDINT</code>、<code>DWORD</code>、<code>REAL</code></li>
          <li><code>DINT</code> 范围：<code>-2147483648 ~ 2147483647</code></li>
          <li><code>UDINT/DWORD</code> 范围：<code>0 ~ 4294967295</code></li>
          <li><code>REAL</code> 是 32 位浮点</li>
          <li>简写不写类型时，默认按 <code>DINT</code> 处理</li>
        </ul>
        <p class="field-help-subtitle">建议直接写完整地址的场景</p>
        <ul class="field-help-list">
          <li>64 位类型：<code>LINT</code>、<code>ULINT</code>、<code>LREAL</code></li>
          <li>字符串：<code>%DB1:20:STRING(16)</code>、<code>%DB1:60:WSTRING(8)</code></li>
          <li>数组：<code>%DB1:40:INT[4]</code></li>
          <li>如果地址宽度和目标类型不容易一眼看清，直接写完整 PLC4X 地址最稳妥</li>
        </ul>
        <p class="field-help-subtitle">重要说明</p>
        <ul class="field-help-list">
          <li><code>DBW</code>、<code>DBD</code> 只表示宽度，不唯一决定数据类型</li>
          <li>最终 PLC4X 地址由“地址 + S7 driver type”共同决定</li>
          <li><code>MODE</code>、<code>SYS</code>、<code>USR</code>、<code>ALM</code> 是订阅模式，不是普通点位地址</li>
        </ul>
      </div>`;
  }

  function renderFieldHelp(helpHtml, helpLabel) {
    return `<span class="field-help"><button type="button" class="field-help-trigger" aria-label="${escapeAttr(helpLabel || "字段说明")}" title="${escapeAttr(helpLabel || "字段说明")}">?</button><span class="field-help-popover" role="tooltip">${helpHtml}</span></span>`;
  }

  function renderFieldControl(field, point) {
    const value = getPath(point, field.path);
    const actual = value === undefined || value === null ? "" : String(value);
    const wide = field.fullWidth ? ' style="grid-column: 1 / -1"' : "";
    const listRefresh = field.listRefresh ? ' data-list-refresh="true"' : "";
    const attrs = ` data-point-path="${escapeAttr(field.path)}" data-value-type="${escapeAttr(field.valueType || "string")}"${listRefresh}`;
    const placeholder = field.placeholder ? ` placeholder="${escapeAttr(field.placeholder)}"` : "";
    const step = field.step !== undefined ? ` step="${escapeAttr(field.step)}"` : "";
    const min = field.min !== undefined ? ` min="${escapeAttr(field.min)}"` : "";
    const disabled = field.disabled ? " disabled" : "";
    const note = field.description ? `<span class="field-description">${escapeHtml(field.description)}</span>` : "";
    const help = field.helpHtml ? renderFieldHelp(field.helpHtml, field.helpLabel || `${field.label}说明`) : "";
    const labelText = `<span class="field-label-text">${escapeHtml(field.label)}${field.required ? ' <span class="field-required">*</span>' : ""}</span>`;
    const labelRow = help ? `<span class="field-label-row">${labelText}${help}</span>` : labelText;
    let control = "";
    if (field.control === "select") {
      control = `<select${attrs}${disabled}>${renderOptions(field.options || [], value, field.allowEmpty !== false)}</select>`;
    } else if (field.control === "textarea") {
      control = `<textarea${attrs} rows="${field.rows || 3}"${placeholder}${disabled}>${escapeHtml(actual)}</textarea>`;
    } else {
      control = `<input${attrs} type="${field.control === "number" ? "number" : "text"}" value="${escapeAttr(actual)}"${placeholder}${step}${min}${disabled}>`;
    }
    return `<label${wide}>${labelRow}${control}${note}</label>`;
  }

  function renderOptions(options, value, allowEmpty = true) {
    const current = value === undefined || value === null ? "" : String(value);
    const items = allowEmpty ? ['<option value=""></option>'] : [];
    options.forEach((option) => {
      const normalized = typeof option === "object" ? option : { value: option, label: option };
      items.push(`<option value="${escapeAttr(normalized.value)}" ${String(normalized.value) === current ? "selected" : ""}>${escapeHtml(normalized.label)}</option>`);
    });
    return items.join("");
  }

  function protocolSchema(protocolCode) {
    return getProtocolSchema(protocolCode) || state.currentLocalProtocol || null;
  }

  function protocolDataTypes(protocolCode) {
    const protocol = protocolSchema(protocolCode);
    return Array.isArray(protocol?.dataTypes) && protocol.dataTypes.length ? protocol.dataTypes : DATA_TYPES;
  }

  function protocolTypeMode(protocol) {
    return protocol?.typeMode || (protocol?.driverTypeEnabled ? "DRIVER_PRIMARY" : "PLATFORM_ONLY");
  }

  function protocolPrimaryTypeField(protocol) {
    if (protocol?.primaryTypeField) {
      return protocol.primaryTypeField;
    }
    if (protocol?.driverTypeEnabled && protocol?.driverTypeField) {
      return protocol.driverTypeField;
    }
    return "dataType";
  }

  function protocolPlatformDataTypeMode(protocol) {
    return protocol?.platformDataTypeMode || (protocolTypeMode(protocol) === "PLATFORM_ONLY" ? "REQUIRED" : "DERIVED_EDITABLE");
  }

  function protocolPrimaryTypeSchemaField(protocol) {
    const path = protocolPrimaryTypeField(protocol);
    return Array.isArray(protocol?.pointFields)
      ? protocol.pointFields.find((field) => field.name === path) || null
      : null;
  }

  function protocolPrimaryTypeLabel(protocol) {
    if (protocolTypeMode(protocol) === "PLATFORM_ONLY") {
      return "数据类型";
    }
    if (protocol?.driverTypeEnabled && protocolPrimaryTypeField(protocol) === protocol?.driverTypeField) {
      return protocol.driverTypeLabel || "协议原生类型";
    }
    const schemaField = protocolPrimaryTypeSchemaField(protocol);
    return schemaField?.label || protocolPrimaryTypeField(protocol);
  }

  function defaultPointDataType(protocolCode) {
    const options = protocolDataTypes(protocolCode);
    if (options.includes("FLOAT")) {
      return "FLOAT";
    }
    if (options.includes("STRING")) {
      return "STRING";
    }
    return options[0] || "FLOAT";
  }

  function buildPlatformDataTypeField(protocolCode, protocol) {
    const platformMode = protocolPlatformDataTypeMode(protocol);
    const description = {
      REQUIRED: "当前协议要求显式填写 dataType。",
      DERIVED_EDITABLE: "dataType 是平台统一类型，可依据主类型字段推导，也允许人工覆盖。",
      DERIVED_READONLY: "dataType 由协议主类型自动推导，这里仅展示平台最终使用的统一类型。",
      ADVANCED: "dataType 仍保留为平台统一类型，但默认不作为主展示字段。"
    }[platformMode] || "dataType 是平台统一类型。";
    return {
      path: "dataType",
      label: "数据类型",
      control: "select",
      valueType: "string",
      allowEmpty: false,
      options: protocolDataTypes(protocolCode).map((value) => ({ value, label: value })),
      description,
      listRefresh: true,
      disabled: platformMode === "DERIVED_READONLY"
    };
  }

  function buildPrimaryTypeField(protocolCode, protocol) {
    const typeMode = protocolTypeMode(protocol);
    if (typeMode === "PLATFORM_ONLY") {
      return null;
    }
    const primaryPath = protocolPrimaryTypeField(protocol);
    if (protocol?.driverTypeEnabled && primaryPath === protocol?.driverTypeField && Array.isArray(protocol.driverDataTypes) && protocol.driverDataTypes.length) {
      return {
        path: primaryPath,
        label: protocol.driverTypeLabel || "协议原生类型",
        control: "select",
        valueType: "string",
        allowEmpty: true,
        options: protocol.driverDataTypes.map((value) => ({ value, label: value })),
        description: `${protocol.driverTypeLabel || "协议原生类型"}是当前协议真正优先使用的驱动类型字段，写入 ${primaryPath}，不会替代平台统一的 dataType。`,
        listRefresh: true
      };
    }
    const schemaField = protocolPrimaryTypeSchemaField(protocol);
    if (!schemaField) {
      return null;
    }
    const config = schemaFieldToLocalField(schemaField);
    return {
      ...config,
      description: `${config.description ? `${config.description} ` : ""}当前协议以该字段作为主类型字段。`,
      listRefresh: true
    };
  }

  function buildTypeEditorFields(protocolCode, protocol) {
    const typeMode = protocolTypeMode(protocol);
    const platformMode = protocolPlatformDataTypeMode(protocol);
    const fields = [];
    if (typeMode === "PLATFORM_ONLY") {
      fields.push(buildPlatformDataTypeField(protocolCode, protocol));
      return fields;
    }
    const primaryField = buildPrimaryTypeField(protocolCode, protocol);
    if (primaryField) {
      fields.push(primaryField);
    }
    if (platformMode !== "ADVANCED") {
      fields.push(buildPlatformDataTypeField(protocolCode, protocol));
    }
    return fields;
  }

  function buildSchemaPointFields(protocol) {
    if (!protocol) {
      return [];
    }
    const primaryPath = protocolPrimaryTypeField(protocol);
    const fields = [];
    if (protocol.driverTypeEnabled && protocol.driverTypeField && protocol.driverTypeField !== primaryPath && Array.isArray(protocol.driverDataTypes) && protocol.driverDataTypes.length) {
      fields.push({
        path: protocol.driverTypeField,
        label: protocol.driverTypeLabel || "协议原生类型",
        control: "select",
        valueType: "string",
        allowEmpty: true,
        options: protocol.driverDataTypes.map((value) => ({ value, label: value })),
        description: `${protocol.driverTypeLabel || "协议原生类型"}用于保存当前协议的原生数据类型，写入 ${protocol.driverTypeField}，不会替代上方的 dataType。`,
        listRefresh: true
      });
    }
    (protocol.pointFields || []).forEach((field) => {
      if (field.name !== primaryPath) {
        fields.push(schemaFieldToLocalField(field));
      }
    });
    return fields;
  }

  function schemaFieldToLocalField(field) {
    const type = String(field?.type || "string").toLowerCase();
    const control = type === "select" || type === "boolean"
      ? "select"
      : (type === "textarea" || type === "object" ? "textarea" : (type === "number" ? "number" : "text"));
    const valueType = type === "boolean" ? "boolean" : (type === "number" ? "integer" : "string");
    const options = type === "boolean"
      ? (field.options && field.options.length ? field.options : ["true", "false"]).map((value) => ({
          value,
          label: String(value) === "true" ? "是" : (String(value) === "false" ? "否" : String(value))
        }))
      : (field.options || []).map((value) => ({ value, label: String(value) }));
    return {
      path: field.name,
      label: field.label || field.name,
      control,
      valueType,
      allowEmpty: !field.required,
      required: field.required,
      options,
      description: schemaFieldDescription(field),
      rows: control === "textarea" ? 4 : undefined,
      fullWidth: control === "textarea",
      listRefresh: field.name === "dataType" || field.name === "additionalConfig.driverDataType"
    };
  }

  function schemaFieldDescription(field) {
    const notes = [];
    if (field?.description) {
      notes.push(field.description);
    }
    if (field?.requiredWhen) {
      notes.push(`条件必填：${field.requiredWhen}`);
    }
    if (field?.storage === "extJson") {
      notes.push("保存位置：additionalConfig");
    }
    return notes.join(" ");
  }

  function resolvePointTypeSummary(point, protocolCode) {
    const protocol = protocolSchema(protocolCode);
    const typeMode = protocolTypeMode(protocol);
    const primaryPath = protocolPrimaryTypeField(protocol);
    const platformValue = point?.dataType;
    const primaryValue = primaryPath === "dataType" ? platformValue : getPath(point, primaryPath);
    if (typeMode === "PLATFORM_ONLY") {
      return platformValue || "-";
    }
    if (hasValue(primaryValue) && hasValue(platformValue) && String(primaryValue) !== String(platformValue)) {
      return `${primaryValue} / ${platformValue}`;
    }
    return primaryValue || platformValue || "-";
  }

  function renderReportBindings(point) {
    const bindings = reportBindings(point);
    return `
      <div class="point-subtable">
        <p class="subtable-note">reportBindings 用于把当前点位绑定到多个上报目标；空行不会保留。</p>
        <div class="table-wrap compact">
          <table>
            <thead><tr><th>deviceName</th><th>productKey</th><th>操作</th></tr></thead>
            <tbody>
              ${bindings.length ? bindings.map((binding, index) => `
                <tr>
                  <td><input type="text" value="${escapeAttr(binding.deviceName || "")}" data-report-binding-index="${index}" data-report-binding-field="deviceName" data-value-type="string"></td>
                  <td><input type="text" value="${escapeAttr(binding.productKey || binding.reportProductKey || "")}" data-report-binding-index="${index}" data-report-binding-field="productKey" data-value-type="string"></td>
                  <td><button type="button" class="danger" data-remove-report-binding="${index}">删除</button></td>
                </tr>`).join("") : `<tr><td colspan="3">暂无上报绑定</td></tr>`}
            </tbody>
          </table>
        </div>
        <div class="inline-actions point-json-actions"><button type="button" data-add-report-binding="true">新增上报绑定</button></div>
      </div>`;
  }

  function renderAlarmRules(point) {
    const rules = alarmRules(point);
    return `
      <div class="point-subtable">
        <p class="subtable-note">alarmRule 会在提交时序列化回顶层 JSON 字符串；只保留有实际内容的规则。</p>
        <div class="table-wrap compact">
          <table>
            <thead><tr><th>ruleId</th><th>ruleName</th><th>operator</th><th>threshold</th><th>duration(s)</th><th>level</th><th>enabled</th><th>description</th><th>操作</th></tr></thead>
            <tbody>
              ${rules.length ? rules.map((rule, index) => `
                <tr>
                  <td><input type="text" value="${escapeAttr(rule.ruleId || "")}" data-alarm-rule-index="${index}" data-alarm-rule-field="ruleId" data-value-type="string"></td>
                  <td><input type="text" value="${escapeAttr(rule.ruleName || "")}" data-alarm-rule-index="${index}" data-alarm-rule-field="ruleName" data-value-type="string"></td>
                  <td><select data-alarm-rule-index="${index}" data-alarm-rule-field="operator" data-value-type="string">${renderOptions([">", ">=", "<", "<=", "==", "!="].map((value) => ({ value, label: value })), rule.operator, false)}</select></td>
                  <td><input type="number" step="0.0001" value="${escapeAttr(rule.threshold ?? "")}" data-alarm-rule-index="${index}" data-alarm-rule-field="threshold" data-value-type="number"></td>
                  <td><input type="number" step="1" value="${escapeAttr(rule.duration ?? "")}" data-alarm-rule-index="${index}" data-alarm-rule-field="duration" data-value-type="integer"></td>
                  <td><select data-alarm-rule-index="${index}" data-alarm-rule-field="level" data-value-type="string">${renderOptions(["INFO", "WARNING", "ERROR", "CRITICAL"].map((value) => ({ value, label: value })), rule.level, true)}</select></td>
                  <td><select data-alarm-rule-index="${index}" data-alarm-rule-field="enabled" data-value-type="boolean">${renderOptions(BOOLEAN_OPTIONS, rule.enabled, true)}</select></td>
                  <td><input type="text" value="${escapeAttr(rule.description || "")}" data-alarm-rule-index="${index}" data-alarm-rule-field="description" data-value-type="string"></td>
                  <td><button type="button" class="danger" data-remove-alarm-rule="${index}">删除</button></td>
                </tr>`).join("") : `<tr><td colspan="9">暂无告警规则</td></tr>`}
            </tbody>
          </table>
        </div>
        <div class="inline-actions point-json-actions"><button type="button" data-add-alarm-rule="true">新增告警规则</button></div>
      </div>`;
  }

  function renderProtocolSectionTitle(protocolCode) {
    if (protocolCode === "MODBUS_TCP" || protocolCode === "MODBUS_RTU") {
      return "协议扩展（Modbus 的 dataType 会直接影响取值长度和解码）";
    }
    return "协议扩展";
  }

  function renderProtocolFields(protocolCode, point) {
    const protocol = state.currentLocalProtocol || getProtocolSchema(protocolCode);
    const schemaFields = buildSchemaPointFields(protocol);
    const fields = schemaFields.length ? schemaFields : (PROTOCOL_FIELDS[protocolCode] || []);
    const typeMode = protocolTypeMode(protocol);
    const primaryPath = protocolPrimaryTypeField(protocol);
    const primaryLabel = protocolPrimaryTypeLabel(protocol);
    const hints = protocol?.pointAddressHints || [];
    const notes = [];
    if (hints.length) {
      notes.push(`当前协议地址示例：${hints.map((item) => `<code>${escapeHtml(item)}</code>`).join(" ")}`);
    }
    if (protocolCode === "SIEMENS_S7") {
      notes.push("S7 地址栏支持简写，例如 DB1.DBX0.0、DB1.DBW0、DB1.DBD4，也支持完整 PLC4X 地址，例如 %DB1:0.0:BOOL、%DB1:4:REAL。MODE/SYS/USR/ALM 只用于订阅模式，不应填在普通点位地址里。");
    }
    if (typeMode === "DRIVER_PRIMARY") {
      notes.push(`当前协议以“${escapeHtml(primaryLabel)}”作为主类型字段，写入 <code>${escapeHtml(primaryPath)}</code>；上方 dataType 仍保留为平台统一类型。`);
    } else if (typeMode === "PROTOCOL_FIELD_PRIMARY") {
      notes.push(`当前协议以“${escapeHtml(primaryLabel)}”作为主类型字段，优先决定协议解析方式；上方 dataType 仍保留为平台统一类型。`);
    }
    if (protocolCode === "MODBUS_TCP" || protocolCode === "MODBUS_RTU") {
      notes.push("Modbus 的 dataType 会直接决定读取长度和寄存器解码方式；下方协议扩展字段主要用于补充兼容配置。");
    } else if (protocolCode === "MITSUBISHI_MC") {
      notes.push("MC 推荐优先使用 3E_BINARY；3E_ASCII 和 4E_BINARY 需要先做现场联机验证，再进入生产批量配置。");
      notes.push("MC 的 STRING 点位必须补 additionalConfig.stringLength；像 D100.3 这样的位偏移地址只适用于 BOOL 点位。");
      notes.push("randomRead/randomWrite 仅适合稀疏标量字点位；如果多个系统同时改同一字，页面虽然可配，但仍需要上层治理避免位覆盖。");
    } else if (protocol?.pointFields?.length) {
      notes.push("下方字段都是协议扩展配置，字段下方的中文备注会说明用途、条件和保存位置。主类型字段如果已经提升到基础信息区，这里不会重复展示。");
    } else {
      notes.push("当前协议没有额外的点位扩展字段。");
    }
    const note = notes.join("<br>");
    if (!fields.length) {
      return `<p class="protocol-point-note">${note}</p>`;
    }
    return `<p class="protocol-point-note">${note}</p><div class="form-grid">${renderFields(fields, point)}</div>`;
  }

  function renderReadonly(label, value) {
    const text = typeof value === "object" ? JSON.stringify(value) : String(value);
    const body = text.length > 36 ? `<code>${escapeHtml(text)}</code>` : `<strong>${escapeHtml(text)}</strong>`;
    return `<div class="readonly-card"><small>${escapeHtml(label)}</small>${body}</div>`;
  }

  function handleLocalPointListClick(event) {
    const trigger = event.target.closest("[data-select-local-point]");
    if (!trigger) {
      return;
    }
    state.selectedLocalPointIndex = Number(trigger.dataset.selectLocalPoint);
    renderLocalPointList();
    renderLocalPointDetail();
  }

  function handleLocalPointDetailInput(event) {
    const alarmField = event.target.closest("[data-alarm-rule-index]");
    if (alarmField) {
      updateAlarmRule(Number(alarmField.dataset.alarmRuleIndex), alarmField.dataset.alarmRuleField, parseInputValue(alarmField.value, alarmField.dataset.valueType));
      syncLocalPointsJson();
      return;
    }
    const bindingField = event.target.closest("[data-report-binding-index]");
    if (bindingField) {
      updateReportBinding(Number(bindingField.dataset.reportBindingIndex), bindingField.dataset.reportBindingField, parseInputValue(bindingField.value, bindingField.dataset.valueType));
      syncLocalPointsJson();
      return;
    }
    const target = event.target.closest("[data-point-path]");
    if (!target) {
      return;
    }
    updateSelectedPath(target.dataset.pointPath, parseInputValue(target.value, target.dataset.valueType));
    syncLocalPointsJson();
    if (target.dataset.listRefresh === "true") {
      renderLocalPointList();
    }
  }

  function handleLocalPointDetailClick(event) {
    if (event.target.closest("[data-add-alarm-rule]")) {
      addAlarmRule();
      return;
    }
    const removeAlarm = event.target.closest("[data-remove-alarm-rule]");
    if (removeAlarm) {
      removeAlarmRule(Number(removeAlarm.dataset.removeAlarmRule));
      return;
    }
    if (event.target.closest("[data-add-report-binding]")) {
      addReportBinding();
      return;
    }
    const removeBinding = event.target.closest("[data-remove-report-binding]");
    if (removeBinding) {
      removeReportBinding(Number(removeBinding.dataset.removeReportBinding));
    }
  }

  function addLocalPoint() {
    const protocolCode = canonicalProtocolForUi($("#localProtocolSelect").value || "MODBUS_TCP");
    const deviceId = $("#localDeviceId").value.trim() || state.localDeviceEditingId || "local-device";
    const pointCode = createUniqueCode(state.localPoints || [], "point");
    const point = defaultPointTemplate(deviceId, protocolCode, { pointCode, pointName: `点位 ${(state.localPoints || []).length + 1}` });
    state.localPoints = (state.localPoints || []).concat(point);
    state.selectedLocalPointIndex = state.localPoints.length - 1;
    renderLocalPointEditor();
  }

  function duplicateLocalPoint() {
    const point = selectedPoint();
    if (!point) {
      return;
    }
    const copy = cloneData(point);
    delete copy.id;
    delete copy.pointId;
    delete copy.createTime;
    delete copy.updateTime;
    delete copy.stableCount;
    delete copy.lastValue;
    delete copy.changeRate;
    delete copy.lastAdjustTime;
    delete copy.reportFieldConflict;
    copy.pointCode = createUniqueCode(state.localPoints || [], `${point.pointCode || "point"}_copy`);
    copy.pointName = `${point.pointName || point.pointCode || "点位"} 副本`;
    if (isPlainObject(copy.additionalConfig) && hasValue(copy.additionalConfig.reportField)) {
      copy.additionalConfig.reportField = copy.pointCode;
    }
    state.localPoints.splice(state.selectedLocalPointIndex + 1, 0, copy);
    state.selectedLocalPointIndex += 1;
    renderLocalPointEditor();
  }

  function removeLocalPoint() {
    const point = selectedPoint();
    if (!point) {
      return;
    }
    if (!window.confirm(`确认删除点位 ${point.pointCode || point.pointName || "当前点位"} 吗？`)) {
      return;
    }
    state.localPoints.splice(state.selectedLocalPointIndex, 1);
    state.selectedLocalPointIndex = state.localPoints.length ? Math.min(state.selectedLocalPointIndex, state.localPoints.length - 1) : -1;
    renderLocalPointEditor();
  }

  function updateSelectedPath(path, value) {
    const point = selectedPoint();
    if (!point) {
      return;
    }
    const previousPointCode = point.pointCode;
    const previousAddress = point.address;
    const previousTopic = getPath(point, "additionalConfig.topic");
    const previousNodeId = getPath(point, "additionalConfig.nodeId");
    const previousReportField = getPath(point, "additionalConfig.reportField");
    const protocol = canonicalProtocolForUi($("#localProtocolSelect").value || "MODBUS_TCP");
    setPath(point, path, value);
    if (path === "pointCode" && hasValue(previousReportField) && String(previousReportField).trim() === String(previousPointCode || "").trim()) {
      setPath(point, "additionalConfig.reportField", value);
      renderLocalPointDetail();
      return;
    }
    if (path === "address") {
      if (protocol === "MQTT" && (!hasValue(previousTopic) || String(previousTopic).trim() === String(previousAddress || "").trim())) {
        setPath(point, "additionalConfig.topic", value);
        renderLocalPointDetail();
        return;
      }
      if (protocol === "OPC_UA" && (!hasValue(previousNodeId) || String(previousNodeId).trim() === String(previousAddress || "").trim())) {
        setPath(point, "additionalConfig.nodeId", value);
        renderLocalPointDetail();
        return;
      }
    }
    if (path === "additionalConfig.topic" && protocol === "MQTT" && (!hasValue(previousAddress) || String(previousAddress).trim() === String(previousTopic || "").trim())) {
      point.address = value;
      renderLocalPointList();
      renderLocalPointDetail();
      return;
    }
    if (path === "additionalConfig.nodeId" && protocol === "OPC_UA" && (!hasValue(previousAddress) || String(previousAddress).trim() === String(previousNodeId || "").trim())) {
      point.address = value;
      renderLocalPointList();
      renderLocalPointDetail();
    }
  }

  function alarmRules(point) {
    const raw = point?.alarmRule;
    if (!raw) {
      return [];
    }
    if (Array.isArray(raw)) {
      return raw.filter(isPlainObject).map((item) => ({ ...item }));
    }
    if (typeof raw === "string") {
      try {
        const parsed = JSON.parse(raw);
        return Array.isArray(parsed) ? parsed.filter(isPlainObject).map((item) => ({ ...item })) : [];
      } catch (error) {
        return [];
      }
    }
    return [];
  }

  function serializeAlarmRules(rules) {
    const normalized = rules.map((rule) => pruneEmpty(isPlainObject(rule) ? { ...rule } : {})).filter((rule) => Object.keys(rule).length);
    return normalized.length ? JSON.stringify(normalized) : "";
  }

  function updateAlarmRule(index, field, value) {
    const point = selectedPoint();
    if (!point) {
      return;
    }
    const rules = alarmRules(point);
    while (rules.length <= index) {
      rules.push({});
    }
    if (value === undefined) {
      delete rules[index][field];
    } else {
      rules[index][field] = value;
    }
    point.alarmRule = serializeAlarmRules(rules);
  }

  function addAlarmRule() {
    const point = selectedPoint();
    if (!point) {
      return;
    }
    const rules = alarmRules(point);
    rules.push({ operator: ">", enabled: true });
    point.alarmRule = serializeAlarmRules(rules);
    renderLocalPointDetail();
    syncLocalPointsJson();
  }

  function removeAlarmRule(index) {
    const point = selectedPoint();
    if (!point) {
      return;
    }
    const rules = alarmRules(point);
    rules.splice(index, 1);
    point.alarmRule = serializeAlarmRules(rules);
    renderLocalPointDetail();
    syncLocalPointsJson();
  }

  function reportBindings(point) {
    const raw = point?.additionalConfig?.reportBindings;
    if (Array.isArray(raw)) {
      return raw.filter(isPlainObject).map((item) => ({ ...item }));
    }
    if (isPlainObject(raw)) {
      return [{ ...raw }];
    }
    return [];
  }

  function updateReportBinding(index, field, value) {
    const point = selectedPoint();
    if (!point) {
      return;
    }
    const bindings = reportBindings(point);
    while (bindings.length <= index) {
      bindings.push({});
    }
    if (value === undefined) {
      delete bindings[index][field];
    } else {
      bindings[index][field] = value;
    }
    point.additionalConfig = isPlainObject(point.additionalConfig) ? point.additionalConfig : {};
    point.additionalConfig.reportBindings = bindings.map((item) => pruneEmpty({ ...item })).filter((item) => Object.keys(item).length);
    if (!point.additionalConfig.reportBindings.length) {
      delete point.additionalConfig.reportBindings;
    }
  }

  function addReportBinding() {
    const point = selectedPoint();
    if (!point) {
      return;
    }
    const bindings = reportBindings(point);
    bindings.push({});
    point.additionalConfig = isPlainObject(point.additionalConfig) ? point.additionalConfig : {};
    point.additionalConfig.reportBindings = bindings;
    renderLocalPointDetail();
    syncLocalPointsJson();
  }

  function removeReportBinding(index) {
    const point = selectedPoint();
    if (!point) {
      return;
    }
    const bindings = reportBindings(point);
    bindings.splice(index, 1);
    point.additionalConfig = isPlainObject(point.additionalConfig) ? point.additionalConfig : {};
    if (bindings.length) {
      point.additionalConfig.reportBindings = bindings;
    } else {
      delete point.additionalConfig.reportBindings;
    }
    renderLocalPointDetail();
    syncLocalPointsJson();
  }

  function formatLocalPointsJson() {
    try {
      const points = parsePointsJson($("#localPointsJson").value || "[]");
      $("#localPointsJson").value = JSON.stringify(points, null, 2);
    } catch (error) {
      toast(`JSON format error: ${error.message}`, true);
    }
  }

  function applyLocalPointsJson() {
    try {
      const currentCode = selectedPoint()?.pointCode || null;
      const deviceId = $("#localDeviceId").value.trim() || "local-device";
      const protocolCode = canonicalProtocolForUi($("#localProtocolSelect").value || "MODBUS_TCP");
      const points = normalizeLocalPoints(parsePointsJson($("#localPointsJson").value || "[]"), deviceId, protocolCode);
      state.localPoints = points;
      const nextIndex = currentCode ? points.findIndex((item) => item.pointCode === currentCode) : -1;
      state.selectedLocalPointIndex = nextIndex >= 0 ? nextIndex : (points.length ? 0 : -1);
      renderLocalPointEditor();
      toast("Point JSON applied to visual editor");
    } catch (error) {
      toast(`JSON apply error: ${error.message}`, true);
    }
  }

  function syncLocalPointsJson() {
    const target = $("#localPointsJson");
    if (target) {
      target.value = JSON.stringify(state.localPoints || [], null, 2);
    }
  }

  function parsePointsJson(text) {
    const parsed = JSON.parse(text || "[]");
    return Array.isArray(parsed) ? parsed : [parsed];
  }

  async function editLocalDeviceOverride(deviceId) {
    try {
      const body = await callApi(`/api/config/local/device/${encodeURIComponent(deviceId)}`);
      const payload = dataOf(body);
      openLocalDeviceForm(payload.bundle);
    } catch (error) {
      toast(error.message || "Load local device failed", true);
    }
  }

  async function saveLocalDevice() {
    try {
      const payload = buildLocalDeviceRequest();
      const editing = Boolean(state.localDeviceEditingId);
      await callApi(editing ? `/api/config/local/device/${encodeURIComponent(state.localDeviceEditingId)}` : "/api/config/local/devices", {
        method: editing ? "PUT" : "POST",
        body: JSON.stringify(payload)
      });
      toast("Local temporary device saved");
      closeLocalDeviceForm();
      await Promise.all([loadDevices(), loadOverview(), loadMonitor()]);
      if (payload.startAfterSave) {
        await loadDevices();
        await showDeviceStatus(payload.device.id);
      }
    } catch (error) {
      toast(error.message || "Save local device failed", true);
    }
  }

  function buildLocalDeviceRequest() {
    const deviceId = $("#localDeviceId").value.trim();
    const deviceName = $("#localDeviceName").value.trim();
    const protocol = canonicalProtocolForUi($("#localProtocolSelect").value || "MODBUS_TCP");
    if (!deviceId) {
      throw new Error("Device ID is required");
    }
    if (!deviceName) {
      throw new Error("Device name is required");
    }
    if (!Array.isArray(state.localPoints) || !state.localPoints.length) {
      throw new Error("At least one point is required");
    }
    const duplicatePointCode = findDuplicatePointCode(state.localPoints);
    if (duplicatePointCode) {
      throw new Error(`Duplicate pointCode: ${duplicatePointCode}`);
    }
    const connection = collectProtocolForm("#localConnectionForm", state.currentLocalProtocol, deviceId);
    const adaptive = readAdaptiveFormValues();
    const points = state.localPoints.map((point, index) => sanitizePoint(point, index, deviceId, adaptive, protocol));
    return {
      device: {
        id: deviceId,
        deviceName,
        protocolType: protocol,
        connectionType: protocol,
        ipAddress: connection.host || undefined,
        port: connection.port || undefined,
        collectionInterval: adaptive.baseCollectionInterval,
        configSource: "local",
        temporaryConfig: true,
        status: "OFFLINE"
      },
      connection: {
        ...connection,
        deviceId,
        connectionType: connection.connectionType || protocol,
        extJson: { ...(connection.extJson || {}), configSource: "local", temporaryConfig: true }
      },
      points,
      overwrite: $("#localOverwrite").checked || Boolean(state.localDeviceEditingId),
      startAfterSave: $("#localStartAfterSave").checked
    };
  }

  function sanitizePoint(point, index, deviceId, adaptive, protocol) {
    if (!hasValue(point?.pointCode)) {
      throw new Error(`Point ${index + 1}: pointCode is required`);
    }
    if (!hasValue(point?.pointName)) {
      throw new Error(`Point ${index + 1} (${point.pointCode}): pointName is required`);
    }
    if (!hasValue(resolvePointAddress(point))) {
      throw new Error(`Point ${index + 1} (${point.pointCode}): address is required`);
    }
    const next = cloneData(point);
    next.pointCode = String(next.pointCode).trim();
    next.pointName = String(next.pointName).trim();
    if (hasValue(next.pointAlias)) {
      next.pointAlias = String(next.pointAlias).trim();
    }
    if (hasValue(next.remark)) {
      next.remark = String(next.remark).trim();
    }
    next.address = hasValue(next.address) ? String(next.address).trim() : String(resolvePointAddress(next)).trim();
    next.deviceId = deviceId;
    next.baseCollectionInterval = adaptive.baseCollectionInterval;
    next.currentCollectionInterval = adaptive.baseCollectionInterval;
    next.minCollectionInterval = adaptive.minCollectionInterval;
    next.maxCollectionInterval = adaptive.maxCollectionInterval;
    next.pointChangeThreshold = adaptive.pointChangeThreshold;
    delete next.deviceName;
    delete next.createTime;
    delete next.updateTime;
    delete next.stableCount;
    delete next.lastValue;
    delete next.changeRate;
    delete next.lastAdjustTime;
    delete next.reportFieldConflict;
    if (Array.isArray(next.alarmRule)) {
      next.alarmRule = serializeAlarmRules(next.alarmRule);
    }
    if (!hasValue(next.alarmRule)) {
      delete next.alarmRule;
    }
    const additionalConfig = isPlainObject(next.additionalConfig) ? next.additionalConfig : {};
    if (protocol === "MQTT" && !hasValue(additionalConfig.topic) && hasValue(next.address)) {
      additionalConfig.topic = next.address;
    }
    if (protocol === "OPC_UA" && !hasValue(additionalConfig.nodeId) && hasValue(next.address)) {
      additionalConfig.nodeId = next.address;
    }
    if (Array.isArray(additionalConfig.reportBindings)) {
      additionalConfig.reportBindings = additionalConfig.reportBindings
        .map((item) => pruneEmpty(isPlainObject(item) ? { ...item } : {}))
        .filter((item) => Object.keys(item).length);
      if (!additionalConfig.reportBindings.length) {
        delete additionalConfig.reportBindings;
      }
    }
    pruneEmpty(additionalConfig);
    next.additionalConfig = { ...additionalConfig, configSource: "local", temporaryConfig: true };
    return next;
  }

  function resolvePointAddress(point) {
    return point?.address
      || getPath(point, "additionalConfig.topic")
      || getPath(point, "additionalConfig.nodeId")
      || getPath(point, "additionalConfig.itemId")
      || "";
  }

  function pointMatches(point, search) {
    const text = [point.pointCode, point.pointName, point.pointAlias, point.address, point.dataType, point.readWrite, getPath(point, "additionalConfig.driverDataType"), getPath(point, "additionalConfig.dptId"), getPath(point, "additionalConfig.dpt"), getPath(point, "additionalConfig.reportField"), getPath(point, "additionalConfig.topic"), getPath(point, "additionalConfig.nodeId")].filter(hasValue).join(" ").toLowerCase();
    return text.includes(search);
  }

  function displayPointName(point, index) {
    return point.pointName || point.pointCode || `Point ${index + 1}`;
  }

  function statusLabel(status) {
    const option = STATUS_OPTIONS.find((item) => Number(item.value) === Number(status));
    return option ? option.label : String(status ?? "-");
  }

  function findDuplicatePointCode(points) {
    const seen = new Set();
    for (const point of points || []) {
      const code = String(point?.pointCode || "").trim().toLowerCase();
      if (!code) {
        continue;
      }
      if (seen.has(code)) {
        return point.pointCode;
      }
      seen.add(code);
    }
    return null;
  }

  function createUniqueCode(points, base) {
    const existing = new Set((points || []).map((item) => String(item?.pointCode || "").trim().toLowerCase()).filter(Boolean));
    const normalizedBase = String(base || "point").trim() || "point";
    let candidate = normalizedBase;
    let counter = 1;
    while (existing.has(candidate.toLowerCase())) {
      candidate = `${normalizedBase}_${counter}`;
      counter += 1;
    }
    return candidate;
  }

  function parseInputValue(value, type) {
    if (type === "integer") {
      if (value === "" || value === null || value === undefined) return undefined;
      const parsed = Number.parseInt(value, 10);
      return Number.isFinite(parsed) ? parsed : undefined;
    }
    if (type === "number") {
      if (value === "" || value === null || value === undefined) return undefined;
      const parsed = Number(value);
      return Number.isFinite(parsed) ? parsed : undefined;
    }
    if (type === "boolean") {
      if (value === "" || value === null || value === undefined) return undefined;
      return String(value) === "true";
    }
    const text = String(value ?? "").trim();
    return text ? text : undefined;
  }

  function getPath(target, path) {
    return String(path || "").split(".").filter(Boolean).reduce((current, key) => (current && current[key] !== undefined ? current[key] : undefined), target);
  }

  function setPath(target, path, value) {
    const parts = String(path || "").split(".").filter(Boolean);
    if (!parts.length || !target) {
      return;
    }
    let current = target;
    for (let index = 0; index < parts.length - 1; index += 1) {
      const key = parts[index];
      if (!isPlainObject(current[key])) {
        current[key] = {};
      }
      current = current[key];
    }
    if (value === undefined) {
      delete current[parts[parts.length - 1]];
    } else {
      current[parts[parts.length - 1]] = value;
    }
  }

  function pruneEmpty(target) {
    if (!isPlainObject(target)) {
      return target;
    }
    Object.keys(target).forEach((key) => {
      const value = target[key];
      if (Array.isArray(value) && !value.length) {
        delete target[key];
      } else if (isPlainObject(value) && !Object.keys(value).length) {
        delete target[key];
      } else if (!hasValue(value)) {
        delete target[key];
      }
    });
    return target;
  }

  function hasValue(value) {
    return value !== undefined && value !== null && !(typeof value === "string" && value.trim() === "");
  }

  function isPlainObject(value) {
    return Boolean(value) && typeof value === "object" && !Array.isArray(value);
  }

  function cloneData(value) {
    return value === undefined ? undefined : JSON.parse(JSON.stringify(value));
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", installLocalPointEditor);
  } else {
    installLocalPointEditor();
  }
})();
