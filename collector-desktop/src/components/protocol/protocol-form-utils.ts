import type { ProtocolFieldConfig } from "@/types/protocol";

export interface ProtocolFieldGroup {
  name: string;
  fields: ProtocolFieldConfig[];
}

export type ProtocolFormModel = Record<string, string | number | boolean | null>;

export type ConnectionPayload = Record<string, unknown>;

export function buildProtocolInitialModel(fields: ProtocolFieldConfig[]): ProtocolFormModel {
  return Object.fromEntries(fields.map((field) => [field.name, normalizeDefaultValue(field)]));
}

export function groupProtocolFields(fields: ProtocolFieldConfig[]): ProtocolFieldGroup[] {
  const groups = new Map<string, ProtocolFieldConfig[]>();
  for (const field of fields) {
    const groupName = field.group || "基础参数";
    const groupFields = groups.get(groupName) ?? [];
    groupFields.push(field);
    groups.set(groupName, groupFields);
  }
  return Array.from(groups.entries()).map(([name, groupFields]) => ({ name, fields: groupFields }));
}

export function validateProtocolModel(fields: ProtocolFieldConfig[], model: ProtocolFormModel): string[] {
  const errors: string[] = [];
  for (const field of fields) {
    const value = model[field.name];
    const label = field.label || field.name;
    if (field.required && isBlank(value)) {
      errors.push(`${label}不能为空`);
      continue;
    }
    if (!isBlank(value) && (field.type === "number" || field.type === "integer") && !Number.isFinite(Number(value))) {
      errors.push(`${label}必须是数字`);
    }
    if (!isBlank(value) && field.options && field.options.length > 0 && !field.options.includes(String(value))) {
      errors.push(`${label}必须是允许的选项`);
    }
  }
  return errors;
}

export function extractProtocolModel(fields: ProtocolFieldConfig[], connection: ConnectionPayload | null | undefined): ProtocolFormModel {
  const source = connection || {};
  return Object.fromEntries(fields.map((field) => {
    const value = readFieldValue(source, field);
    return [field.name, value === undefined ? normalizeDefaultValue(field) : normalizeFormValue(field, value)];
  }));
}

export function buildConnectionPayload(fields: ProtocolFieldConfig[], model: ProtocolFormModel, base: ConnectionPayload = {}): ConnectionPayload {
  const payload = clonePlainObject(base);
  if (!isPlainObject(payload.extJson)) {
    payload.extJson = {};
  }
  for (const field of fields) {
    const value = model[field.name];
    if (value === undefined) {
      continue;
    }
    writeFieldValue(payload, field, value);
  }
  return payload;
}

const GROUP_LABELS: Record<string, string> = {
  connection: "基础连接",
  connect: "基础连接",
  network: "网络参数",
  transport: "传输参数",
  serial: "串口参数",
  protocol: "协议参数",
  auth: "认证参数",
  security: "安全认证",
  advanced: "高级参数",
  performance: "性能参数",
  cache: "缓存参数",
  mqtt: "MQTT 参数",
  modbus: "Modbus 参数",
  opcua: "OPC UA 参数",
  opc_ua: "OPC UA 参数",
  http: "HTTP 参数"
};

const FIELD_LABELS: Record<string, string> = {
  host: "主机/IP",
  hostname: "主机/IP",
  ip: "IP",
  ipaddress: "IP",
  address: "地址",
  url: "URL",
  endpoint: "端点",
  port: "端口",
  unitid: "从站ID",
  slaveid: "从站ID",
  stationid: "站号",
  deviceid: "设备ID",
  devicename: "设备名",
  clientid: "客户端ID",
  productkey: "ProductKey",
  username: "用户名",
  password: "密码",
  token: "令牌",
  topic: "Topic",
  nodeid: "节点ID",
  timeout: "协议超时",
  connecttimeout: "连接超时",
  connectiontimeout: "连接超时",
  readtimeout: "读取超时",
  writetimeout: "写入超时",
  requesttimeout: "请求超时",
  retry: "重试",
  retries: "重试",
  maxretry: "最大重试",
  keepalive: "保活",
  baudrate: "波特率",
  databits: "数据位",
  stopbits: "停止位",
  parity: "校验",
  byteorder: "字节序",
  wordorder: "字序",
  endian: "端序",
  registerbase: "寄存器基址",
  maxregistersperrequest: "最大寄存器数",
  maxcoilsperrequest: "最大线圈数",
  plc4xconnectionstring: "PLC4X连接串",
  pingaddress: "PLC4X Ping",
  plc4xpingaddress: "PLC4X Ping",
  path: "路径",
  database: "数据库",
  mode: "模式",
  qos: "QoS",
  ssl: "SSL",
  tls: "TLS"
};

export function displayGroupName(name: string): string {
  const trimmed = String(name || "").trim();
  if (!trimmed) {
    return "基础参数";
  }
  if (hasChinese(trimmed)) {
    return trimmed;
  }
  const key = normalizeKey(trimmed);
  return GROUP_LABELS[key] || inferGroupName(key);
}

export function displayProtocolFieldLabel(field: ProtocolFieldConfig): string {
  const byName = FIELD_LABELS[normalizeKey(field.name)];
  if (byName) {
    return byName;
  }
  const label = String(field.label || "").trim();
  const byLabel = FIELD_LABELS[normalizeKey(label)];
  if (byLabel) {
    return byLabel;
  }
  if (label) {
    return shortenLabel(label);
  }
  return FIELD_LABELS[normalizeKey(field.name)] || shortenLabel(field.name);
}

export function isWideProtocolField(field: ProtocolFieldConfig): boolean {
  const key = normalizeKey([field.name, field.label, field.description].filter(Boolean).join(" "));
  return /connectionstring|pingaddress|endpoint|url|jdbc|dsn|path|certificate|cert|privatekey/.test(key);
}

export function normalizeProtocolFieldKey(value: string): string {
  return normalizeKey(value);
}

export function getPathValue(source: unknown, path: string): unknown {
  if (!source || !path) {
    return undefined;
  }
  const segments = path.split(".").filter(Boolean);
  let current: unknown = source;
  for (const segment of segments) {
    if (!isPlainObject(current)) {
      return undefined;
    }
    current = current[segment];
  }
  return current;
}

export function setPathValue(target: Record<string, unknown>, path: string, value: unknown): void {
  const segments = path.split(".").filter(Boolean);
  if (segments.length === 0) {
    return;
  }
  let current: Record<string, unknown> = target;
  for (const segment of segments.slice(0, -1)) {
    const next = current[segment];
    if (!isPlainObject(next)) {
      current[segment] = {};
    }
    current = current[segment] as Record<string, unknown>;
  }
  current[segments[segments.length - 1]] = value;
}

function normalizeDefaultValue(field: ProtocolFieldConfig): string | number | boolean | null {
  const rawValue = field.defaultValue;
  if (field.type === "boolean") {
    return rawValue === "true" || rawValue === "1";
  }
  if (field.type === "number") {
    if (rawValue === undefined || rawValue === null || rawValue === "") {
      return null;
    }
    const numberValue = Number(rawValue);
    return Number.isFinite(numberValue) ? numberValue : null;
  }
  return rawValue ?? "";
}

function readFieldValue(connection: ConnectionPayload, field: ProtocolFieldConfig): unknown {
  if (field.storage === "extJson") {
    return getPathValue(connection.extJson, field.name);
  }
  return getPathValue(connection, field.name);
}

function writeFieldValue(payload: ConnectionPayload, field: ProtocolFieldConfig, value: unknown): void {
  if (field.storage === "extJson") {
    const extJson = isPlainObject(payload.extJson) ? payload.extJson : {};
    payload.extJson = extJson;
    setPathValue(extJson, field.name, value);
    return;
  }
  setPathValue(payload, field.name, value);
}

function normalizeFormValue(field: ProtocolFieldConfig, value: unknown): string | number | boolean | null {
  if (value === null) {
    return null;
  }
  if (field.type === "boolean") {
    return value === true || value === "true" || value === "1" || value === 1;
  }
  if (field.type === "number" || field.type === "integer") {
    const numberValue = Number(value);
    return Number.isFinite(numberValue) ? numberValue : null;
  }
  if (typeof value === "number" || typeof value === "boolean") {
    return value;
  }
  return typeof value === "string" ? value : String(value);
}

function clonePlainObject(source: ConnectionPayload): ConnectionPayload {
  return JSON.parse(JSON.stringify(source || {})) as ConnectionPayload;
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function isBlank(value: unknown): boolean {
  return value === undefined || value === null || String(value).trim() === "";
}

function normalizeKey(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9]/g, "");
}

function hasChinese(value: string): boolean {
  return /[\u4e00-\u9fa5]/.test(value);
}

function inferGroupName(key: string): string {
  if (key.includes("connect")) return "基础连接";
  if (key.includes("network")) return "网络参数";
  if (key.includes("serial")) return "串口参数";
  if (key.includes("auth") || key.includes("credential")) return "认证参数";
  if (key.includes("security") || key.includes("ssl") || key.includes("tls")) return "安全认证";
  if (key.includes("protocol") || key.includes("modbus") || key.includes("opc") || key.includes("mqtt")) return "协议参数";
  if (key.includes("advanced") || key.includes("extra") || key.includes("extend")) return "高级参数";
  return "扩展参数";
}

function shortenLabel(label: string): string {
  const trimmed = String(label || "").trim();
  if (!trimmed) {
    return "参数";
  }
  const normalized = normalizeKey(trimmed);
  if (FIELD_LABELS[normalized]) {
    return FIELD_LABELS[normalized];
  }
  if (/主机|地址|host/i.test(trimmed) && /ip|IP|主机/.test(trimmed)) return "主机/IP";
  if (/端口|port/i.test(trimmed)) return "端口";
  if (/从站|slave|unit/i.test(trimmed)) return "从站ID";
  if (/read\s*timeout|读取超时/i.test(trimmed)) return "读取超时";
  if (/protocol\s*timeout|协议超时/i.test(trimmed)) return "协议超时";
  if (/超时|timeout/i.test(trimmed)) return "超时";
  if (/重试|retry/i.test(trimmed)) return "重试";
  if (/波特|baud/i.test(trimmed)) return "波特率";
  if (/字节序|byte/i.test(trimmed)) return "字节序";
  if (/字序|word/i.test(trimmed)) return "字序";
  if (/max.*register|最大.*寄存器/i.test(trimmed)) return "最大寄存器数";
  if (/max.*coil|最大.*线圈/i.test(trimmed)) return "最大线圈数";
  if (/plc4x.*connection/i.test(trimmed)) return "PLC4X连接串";
  if (/plc4x.*ping/i.test(trimmed)) return "PLC4X Ping";
  if (/寄存器|register/i.test(trimmed)) return "寄存器";
  if (/客户端|client/i.test(trimmed)) return "客户端ID";
  if (/用户名|user/i.test(trimmed)) return "用户名";
  if (/密码|password/i.test(trimmed)) return "密码";
  if (hasChinese(trimmed) && trimmed.length > 10) {
    return `${trimmed.slice(0, 9)}…`;
  }
  if (!hasChinese(trimmed) && trimmed.length > 18) {
    return `${trimmed.slice(0, 16)}…`;
  }
  return trimmed;
}
