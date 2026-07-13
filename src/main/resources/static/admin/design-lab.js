(function () {
  const themes = [
    {
      id: "aerial",
      name: "A. 空灵玻璃",
      note: "轻量玻璃感，适合强调总览和现代运维感。",
      colors: ["#2376ea", "#6ecbff", "#2ec2b0", "#ffffff"]
    },
    {
      id: "anchor",
      name: "B. 稳态主控",
      note: "更稳、更像工业主控台，强调边界和秩序。",
      colors: ["#145ebf", "#264f78", "#dce7f3", "#ffffff"]
    },
    {
      id: "pulse",
      name: "C. 信号脉冲",
      note: "提高状态对比，适合需要更强告警感知的场景。",
      colors: ["#ff8f4a", "#ffcf87", "#1ec7aa", "#17344f"]
    },
    {
      id: "forge",
      name: "D. 工业锻造",
      note: "更硬朗的面板感和更少圆角，偏传统 SCADA 气质。",
      colors: ["#1b2f44", "#5d7690", "#d4dde8", "#ffffff"]
    },
    {
      id: "beacon",
      name: "E. 蓝绿信标",
      note: "蓝绿监控向，强调实时数据和诊断感。",
      colors: ["#16b8ad", "#71ddd3", "#154765", "#ffffff"]
    }
  ];

  function isPreviewMode() {
    const search = new URLSearchParams(window.location.search);
    return search.get("preview") === "1" || search.get("lab") === "1";
  }

  function currentThemeId() {
    return localStorage.getItem("collectorDesignTheme") || "anchor";
  }

  function applyTheme(themeId) {
    const nextTheme = themes.find((item) => item.id === themeId) || themes[1];
    document.body.classList.remove(...themes.map((item) => `theme-${item.id}`));
    document.body.classList.add(`theme-${nextTheme.id}`);
    localStorage.setItem("collectorDesignTheme", nextTheme.id);

    document.querySelectorAll(".design-theme-chip").forEach((button) => {
      button.classList.toggle("is-active", button.dataset.themeId === nextTheme.id);
    });

    const nameNode = document.querySelector("#designLabCurrentName");
    const noteNode = document.querySelector("#designLabCurrentNote");
    if (nameNode) {
      nameNode.textContent = nextTheme.name;
    }
    if (noteNode) {
      noteNode.textContent = nextTheme.note;
    }
  }

  function renderThemeBar() {
    const shell = document.querySelector("#designLabBar");
    const container = document.querySelector("#designLabThemes");
    if (!shell || !container) {
      return;
    }
    shell.classList.remove("hidden");
    container.innerHTML = themes.map((theme) => `
      <button type="button" class="design-theme-chip" data-theme-id="${theme.id}">
        <div class="design-theme-swatch">
          ${theme.colors.map((color) => `<i style="background:${color}"></i>`).join("")}
        </div>
        <div>
          <strong>${theme.name}</strong>
          <small>${theme.note}</small>
        </div>
      </button>
    `).join("");

    container.querySelectorAll(".design-theme-chip").forEach((button) => {
      button.addEventListener("click", () => applyTheme(button.dataset.themeId));
    });
    applyTheme(currentThemeId());
  }

  function previewDevice(id, name, protocolType, ipAddress, port, collectionInterval, status, source, location, tags) {
    return {
      id,
      deviceId: id,
      deviceName: name,
      protocolType,
      connectionType: protocolType,
      ipAddress,
      port,
      host: ipAddress,
      collectionInterval,
      status,
      configSource: source,
      temporaryConfig: source === "local",
      location,
      tags
    };
  }

  function pointTemplate(deviceId, seed) {
    return {
      deviceId,
      pointId: seed.pointCode,
      pointCode: seed.pointCode,
      pointName: seed.pointName,
      pointAlias: seed.pointAlias || "",
      address: seed.address,
      registerAddress: seed.address,
      pointAddress: seed.address,
      dataType: seed.dataType || "FLOAT",
      readWrite: seed.readWrite || "R",
      collectionMode: seed.collectionMode || "POLLING",
      status: seed.status ?? 1,
      unit: seed.unit || "",
      scalingFactor: seed.scalingFactor ?? 1,
      offset: seed.offset ?? 0,
      deadband: seed.deadband ?? 0.2,
      minValue: seed.minValue ?? 0,
      maxValue: seed.maxValue ?? 100,
      precision: seed.precision ?? 1,
      unitId: seed.unitId ?? 1,
      commonAddress: seed.commonAddress ?? 1,
      priority: seed.priority ?? 1,
      cacheEnabled: 1,
      cacheDuration: 120,
      alarmEnabled: seed.alarmEnabled ?? 1,
      alarmRule: JSON.stringify(seed.alarmRule || [{ op: ">", value: seed.alarmThreshold ?? 80, level: "WARN" }]),
      additionalConfig: {
        reportEnabled: true,
        reportField: seed.reportField || seed.pointCode,
        changeThreshold: seed.changeThreshold ?? 0.3,
        changeMinIntervalMs: 1000,
        eventEnabled: seed.eventEnabled ?? true,
        eventMinIntervalMs: 3000,
        streamEnabled: true,
        historyEnabled: true,
        registerType: seed.registerType || "HOLDING_REGISTER",
        byteOrder: "BIG_ENDIAN",
        wordOrder: "BIG_ENDIAN"
      }
    };
  }

  function previewDataset() {
    const devices = [
      previewDevice("boiler-line-01", "1号锅炉采集机", "MODBUS_TCP", "10.20.1.11", 502, 2000, "ONLINE", "sync", "北侧锅炉房", ["热力", "主站"]),
      previewDevice("mixer-line-04", "混配站4号机", "OPC_UA", "10.20.3.18", 4840, 1500, "RUNNING", "sync", "搅拌工段", ["工艺", "配料"]),
      previewDevice("water-pump-02", "循环泵二组", "MODBUS_RTU", "192.168.31.87", 9600, 2500, "OFFLINE", "local", "中水区", ["泵房", "临时"]),
      previewDevice("packing-iot-08", "包装线网关", "MQTT", "10.30.6.66", 1883, 3000, "ONLINE", "sync", "包装车间", ["边缘", "网关"])
    ];

    const runtimeByDevice = {
      "boiler-line-01": [
        ["steam_pressure", "蒸汽压力", "40001", 1.23, "MPa"],
        ["steam_temp", "蒸汽温度", "40003", 186.4, "℃"],
        ["drum_level", "汽包液位", "40005", 51.7, "%"],
        ["feed_flow", "给水流量", "40009", 34.8, "t/h"],
        ["o2_ratio", "含氧量", "40011", 4.7, "%"],
        ["blower_freq", "鼓风机频率", "40013", 39.2, "Hz"],
        ["fan_current", "引风机电流", "40015", 27.6, "A"],
        ["coal_temp", "入炉煤温", "40017", 62.3, "℃"],
        ["water_temp", "给水温度", "40019", 84.5, "℃"],
        ["main_valve", "主汽阀开度", "40021", 72.1, "%"],
        ["return_press", "回水压力", "40023", 0.62, "MPa"],
        ["furnace_neg", "炉膛负压", "40025", -182, "Pa"],
        ["desulfur_flow", "脱硫流量", "40027", 12.4, "m3/h"],
        ["so2_level", "SO2浓度", "40029", 26.8, "mg/m3"],
        ["smoke_temp", "排烟温度", "40031", 144.6, "℃"]
      ],
      "mixer-line-04": [
        ["tank_level", "主罐液位", "ns=2;s=Tank.Level", 67.4, "%"],
        ["tank_temp", "主罐温度", "ns=2;s=Tank.Temp", 43.8, "℃"],
        ["agitator_speed", "搅拌转速", "ns=2;s=Agitator.Speed", 1280, "rpm"],
        ["agitator_torque", "搅拌扭矩", "ns=2;s=Agitator.Torque", 38.6, "N·m"],
        ["batch_stage", "批次阶段", "ns=2;s=Batch.Stage", 3, "段"],
        ["binder_ratio", "粘合剂比率", "ns=2;s=Recipe.Binder", 18.5, "%"],
        ["powder_ratio", "粉料比率", "ns=2;s=Recipe.Powder", 52.2, "%"],
        ["moisture", "含水率", "ns=2;s=Tank.Moisture", 10.6, "%"],
        ["motor_temp", "主电机温度", "ns=2;s=Motor.Temp", 56.7, "℃"],
        ["motor_current", "主电机电流", "ns=2;s=Motor.Current", 22.4, "A"],
        ["vacuum", "真空度", "ns=2;s=Tank.Vacuum", -68.9, "kPa"],
        ["feed_a", "投料A瞬时量", "ns=2;s=Feed.A", 14.2, "kg/min"],
        ["feed_b", "投料B瞬时量", "ns=2;s=Feed.B", 11.9, "kg/min"],
        ["feed_c", "投料C瞬时量", "ns=2;s=Feed.C", 8.3, "kg/min"],
        ["discharge_gate", "出料门开度", "ns=2;s=Gate.Opening", 15.0, "%"]
      ],
      "water-pump-02": [
        ["pump_status", "泵运行状态", "30001", 0, ""],
        ["pump_freq", "变频器频率", "30003", 0, "Hz"],
        ["pipe_press", "管网压力", "30005", 0.38, "MPa"],
        ["flow_rate", "瞬时流量", "30007", 18.3, "m3/h"],
        ["inlet_temp", "入口温度", "30009", 23.1, "℃"],
        ["outlet_temp", "出口温度", "30011", 24.9, "℃"],
        ["bearing_temp", "轴承温度", "30013", 35.4, "℃"],
        ["cabinet_humi", "柜内湿度", "30015", 61.2, "%"]
      ],
      "packing-iot-08": [
        ["line_speed", "包装线速度", "pack/line/speed", 42.1, "箱/min"],
        ["error_count", "当班异常数", "pack/line/errors", 2, "次"],
        ["carton_stock", "纸箱余量", "pack/line/carton", 684, "个"],
        ["label_stock", "标签余量", "pack/line/label", 3210, "张"],
        ["seal_temp", "封口温度", "pack/line/sealTemp", 178.5, "℃"],
        ["camera_ok", "视觉通过率", "pack/line/vision", 99.6, "%"]
      ]
    };

    const pointConfigs = {};
    const runtimeValues = {};

    Object.entries(runtimeByDevice).forEach(([deviceId, rows]) => {
      pointConfigs[deviceId] = rows.map(([pointCode, pointName, address, value, unit], index) => pointTemplate(deviceId, {
        pointCode,
        pointName,
        address,
        value,
        unit,
        dataType: typeof value === "number" && Number.isInteger(value) ? "INT" : "FLOAT",
        precision: typeof value === "number" && !Number.isInteger(value) ? 1 : 0,
        priority: index < 4 ? 2 : 1,
        alarmThreshold: typeof value === "number" ? value * 1.15 : 1
      }));

      runtimeValues[deviceId] = Object.fromEntries(rows.map(([pointCode, pointName, address, value, unit], index) => {
        const acceptable = !(deviceId === "water-pump-02" && index < 2);
        return [pointCode, {
          pointId: pointCode,
          pointCode,
          pointName,
          address,
          registerAddress: address,
          pointAddress: address,
          dataType: typeof value === "number" && Number.isInteger(value) ? "INT" : "FLOAT",
          readWrite: index % 5 === 0 ? "RW" : "R",
          scale: 1,
          scalingFactor: 1,
          value,
          rawValue: value,
          unit,
          processingTime: 6 + index,
          quality: acceptable ? "GOOD" : "BAD",
          qualityAcceptable: acceptable
        }];
      }));
    });

    return { devices, pointConfigs, runtimeValues };
  }

  window.__collectorDesignLab = {
    isPreviewMode,
    themes,
    renderThemeBar,
    applyTheme,
    currentThemeId,
    previewDataset
  };

  document.addEventListener("DOMContentLoaded", () => {
    if (!isPreviewMode()) {
      return;
    }
    document.body.classList.add("preview-mode");
    renderThemeBar();
  });
})();
