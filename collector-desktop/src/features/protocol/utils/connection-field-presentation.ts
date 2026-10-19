import { displayFullProtocolFieldLabel } from "@/components/protocol/protocol-form-utils";
import type { ProtocolFieldConfig } from "@/types/protocol";

export interface ConnectionFieldPresentation {
  label: string;
  controlWidth: number;
  slotWidth: number;
  unit: string;
  multiline: boolean;
  secret: boolean;
  help: string;
}

type ReviewedPresentation = readonly [label: string, controlWidth: number, slotWidth: number, unit: string, multiline: boolean];

// 已确认目标图的逐字段展示尺寸；不定义业务字段，不改变 Schema 或长度校验。
// 未列出的协议/字段继续由真实 Schema 的类型、选项和输入语义渲染。
const REVIEWED_PRESENTATION: Readonly<Record<string, Readonly<Record<string, ReviewedPresentation>>>> = {
  "MODBUS_TCP": {
    "host": ["设备主机", 320, 320, "", false],
    "port": ["端口", 112, 150, "", false],
    "slaveId": ["从站地址", 104, 150, "", false],
    "byteOrder": ["字节序", 170, 170, "", false],
    "parity": ["校验方式", 120, 160, "", false],
    "plc4xConnectionString": ["PLC4X 连接串", 560, 560, "", false],
    "pingAddress": ["PLC4X 探测地址", 400, 400, "", false],
    "maxRegistersPerRequest": ["单次最大寄存器数", 128, 160, "个", false],
    "maxCoilsPerRequest": ["单次最大线圈数", 128, 160, "个", false],
    "readTimeout": ["读取超时", 144, 160, "ms", false],
    "timeout": ["协议超时", 144, 160, "ms", false],
    "addressMode": ["地址模式", 180, 180, "", false],
  },
  "ETHERNET_IP": {
    "host": ["设备地址", 320, 320, "", false],
    "port": ["端口", 112, 160, "", false],
    "communicationPath": ["通信路由", 360, 360, "", false],
    "backplane": ["背板端口", 104, 160, "", false],
    "slot": ["槽位", 104, 160, "", false],
    "maxFieldsPerRequest": ["单次最大字段数", 128, 160, "个", false],
    "bigEndian": ["大端模式", 80, 160, "", false],
    "forceUnconnectedOperation": ["强制无连接操作", 80, 160, "", false],
    "tcpKeepAlive": ["TCP 保活", 80, 160, "", false],
    "tcpNoDelay": ["TCP 立即发送", 80, 160, "", false],
    "plc4xConnectionString": ["PLC4X 连接串", 560, 560, "", false],
    "readTimeout": ["读取超时", 144, 160, "ms", false],
    "timeout": ["协议超时", 144, 160, "ms", false],
  },
  "OPC_UA": {
    "url": ["主端点 URL", 560, 560, "", false],
    "endpointUrl": ["端点 URL（别名）", 560, 560, "", false],
    "endpoint": ["端点地址别名", 560, 560, "", false],
    "host": ["主机", 320, 320, "", false],
    "port": ["端口", 112, 150, "", false],
    "discovery": ["使用发现端点", 80, 160, "", false],
    "authType": ["认证类型", 180, 180, "", false],
    "securityPolicy": ["安全策略", 280, 280, "", false],
    "messageSecurity": ["消息安全模式", 220, 220, "", false],
    "securityMode": ["安全模式别名", 240, 240, "", false],
    "username": ["用户名", 240, 240, "", false],
    "password": ["密码", 240, 240, "", false],
    "authParams": ["认证参数（别名）", 560, 560, "", false],
    "keyStoreFile": ["客户端密钥库文件", 420, 420, "", false],
    "keyStoreType": ["客户端密钥库类型", 180, 180, "", false],
    "keyStorePassword": ["客户端密钥库密码", 260, 260, "", false],
    "clientCertPath": ["客户端证书别名", 360, 360, "", false],
    "clientCertPassword": ["客户端证书密码（别名）", 260, 260, "", false],
    "trustStoreFile": ["信任库文件", 420, 420, "", false],
    "trustStoreType": ["信任库类型", 180, 180, "", false],
    "trustStorePassword": ["信任库密码", 260, 260, "", false],
    "serverCertificateFile": ["固定服务端证书文件", 420, 420, "", false],
    "endpointHost": ["端点主机覆盖", 320, 320, "", false],
    "endpointPort": ["端点端口覆盖", 112, 160, "", false],
    "channelLifetime": ["安全通道生命周期", 144, 160, "ms", false],
    "sessionTimeout": ["会话超时", 144, 160, "ms", false],
    "negotiationTimeout": ["协商超时", 144, 160, "ms", false],
    "connectTimeoutMs": ["连接超时（毫秒别名）", 144, 160, "ms", false],
    "connectTimeout": ["连接超时（别名）", 144, 160, "ms", false],
    "requestTimeout": ["请求超时", 144, 160, "ms", false],
    "requestTimeoutMs": ["请求超时（毫秒别名）", 144, 160, "ms", false],
    "subscriptionInterval": ["订阅间隔", 144, 160, "ms", false],
    "nodeIdAliasMode": ["NodeId 别名模式", 160, 160, "", false],
    "nodeIdPrefix": ["NodeId 前缀", 360, 360, "", false],
    "maxFieldsPerRequest": ["单次最大字段数", 128, 160, "个", false],
  },
  "MQTT": {
    "url": ["Broker URL（主）", 560, 560, "", false],
    "brokerUrl": ["Broker URL（别名）", 560, 560, "", false],
    "host": ["Broker 主机", 320, 320, "", false],
    "port": ["Broker 端口", 112, 160, "", false],
    "clientId": ["客户端 ID", 320, 320, "", false],
    "version": ["MQTT 版本", 120, 160, "", false],
    "username": ["用户名", 240, 240, "", false],
    "password": ["密码", 240, 240, "", false],
    "sslEnabled": ["启用 SSL", 80, 160, "", false],
    "subscribeTopics": ["默认订阅主题", 520, 520, "", false],
    "subscribeQos": ["默认订阅 QoS", 120, 160, "", false],
    "publishTopic": ["默认发布主题", 480, 480, "", false],
    "publishQos": ["发布 QoS", 120, 160, "", false],
    "retained": ["保留发布", 80, 160, "", false],
    "cleanSession": ["清理会话", 80, 160, "", false],
    "autoReconnect": ["框架管理重连", 80, 160, "", false],
    "insecureSkipVerify": ["跳过 TLS 证书校验（不安全）", 80, 196, "", false],
    "connectTimeout": ["连接超时", 144, 160, "ms", false],
    "heartbeatInterval": ["心跳间隔", 144, 160, "ms", false],
    "readTimeout": ["读取超时", 144, 160, "ms", false],
    "sessionExpiryInterval": ["会话过期间隔", 144, 160, "s", false],
    "receiveMaximum": ["最大接收数量", 128, 160, "条", false],
    "willTopic": ["遗嘱主题", 480, 480, "", false],
    "willMessage": ["遗嘱消息正文", 560, 560, "", true],
    "willQos": ["遗嘱 QoS", 120, 160, "", false],
    "willRetained": ["遗嘱保留", 80, 160, "", false],
    "authTopic": ["认证主题", 480, 480, "", false],
    "messageProperties": ["MQTT v5 消息属性", 560, 560, "", false],
    "maxPendingMessages": ["最大待处理消息数", 128, 160, "条", false],
    "dispatchBatchSize": ["分发批量大小", 128, 160, "条", false],
    "dispatchFlushInterval": ["分发刷新间隔", 144, 160, "ms", false],
    "overflowStrategy": ["溢出策略", 190, 190, "", false],
    "productKey": ["产品 Key", 300, 300, "", false],
    "deviceSecret": ["设备密钥", 280, 280, "", false],
    "authParams": ["扩展认证参数", 560, 560, "", false],
  },
  "MODBUS_RTU": {
    "serialPort": ["串口", 220, 220, "", false],
    "baudRate": ["波特率", 136, 160, "bps", false],
    "dataBits": ["数据位", 104, 160, "位", false],
    "stopBits": ["停止位", 104, 160, "位", false],
    "parity": ["校验方式", 120, 160, "", false],
    "slaveId": ["从站地址", 104, 150, "", false],
    "byteOrder": ["字节序", 170, 170, "", false],
    "interFrameDelay": ["帧间延迟", 144, 160, "ms", false],
    "plc4xProtocolCode": ["PLC4X 协议代码", 160, 160, "", false],
    "plc4xConnectionString": ["PLC4X 连接串", 560, 560, "", false],
    "maxRegistersPerRequest": ["单次最大寄存器数", 128, 160, "个", false],
    "maxCoilsPerRequest": ["单次最大线圈数", 128, 160, "个", false],
    "readTimeout": ["读取超时", 144, 160, "ms", false],
    "timeout": ["协议超时", 144, 160, "ms", false],
    "addressMode": ["地址模式", 180, 180, "", false],
  },
};
const GROUP_HELP: Record<string, string> = {
  connection: "地址与连接入口",
  protocol: "设备寻址与协议设置",
  security: "认证方式、证书与凭据",
  topic: "订阅、发布与遗嘱",
  advanced: "超时、驱动与性能设置"
};

const FIELD_HELP: Record<string, string> = {
  communicationPath: "完整路由优先于背板端口和槽位；使用端口、地址成对的逗号序列。",
  plc4xConnectionString: "显式连接串会覆盖设备地址、端口与自动路由参数。",
  readTimeout: "读取阶段超时，与协议超时分别配置。",
  timeout: "协议层通用超时。"
};

export function connectionGroupHelp(name: string): string {
  return GROUP_HELP[name] || "协议扩展设置";
}

export function connectionFieldPresentation(protocol: string, field: ProtocolFieldConfig): ConnectionFieldPresentation {
  const reviewed = REVIEWED_PRESENTATION[protocol]?.[field.name];
  const label = reviewed?.[0] || displayFullProtocolFieldLabel(field);
  const controlWidth = reviewed?.[1] ?? inferredWidth(field);
  const slotWidth = reviewed?.[2] ?? Math.max(controlWidth, Math.min(560, textWidth(label) + 36), controlWidth <= 180 ? 160 : controlWidth);
  const secret = field.type === "password" || /password|secret|token|privatekey/i.test(field.name);
  const multiline = !secret && (reviewed?.[4] || field.type === "object" || field.type === "textarea");
  return {
    label,
    controlWidth,
    slotWidth,
    unit: reviewed?.[3] ?? inferredUnit(field),
    multiline,
    secret,
    help: fieldHelp(field)
  };
}

function textWidth(value: string): number {
  return Array.from(value).reduce((width, char) => width + ((char.codePointAt(0) || 0) > 255 ? 12 : 7), 0);
}

function inferredWidth(field: ProtocolFieldConfig): number {
  if (field.type === "boolean") return 80;
  if (field.options?.length) return Math.max(120, Math.min(560, Math.max(...field.options.map(textWidth)) + 44));
  const name = field.name.toLowerCase();
  if (field.type === "number" || field.type === "integer") {
    if (/port$/.test(name)) return 112;
    if (/slave|unitid|station|slot|backplane|bits/.test(name)) return 104;
    return /timeout|interval|delay|duration|keepalive/.test(name) ? 144 : 128;
  }
  if (field.type === "object" || field.type === "textarea" || /connectionstring|endpoint|url|jdbc|dsn|properties/.test(name)) return 560;
  if (/topic/.test(name)) return 480;
  if (/path|certificate|cert|keystore|truststore/.test(name)) return 420;
  if (/host|ipaddress/.test(name)) return 320;
  return 220;
}

function inferredUnit(field: ProtocolFieldConfig): string {
  const source = [field.label, field.description].filter(Boolean).join(" ");
  if (/毫秒|\bms\b/i.test(source)) return "ms";
  if (/秒|\bseconds?\b|\(s\)/i.test(source)) return "s";
  return "";
}

function fieldHelp(field: ProtocolFieldConfig): string {
  const explanations: string[] = [];
  const specific = FIELD_HELP[field.name];
  const parenthetical = Array.from((field.label || "").matchAll(/[（(]([^）)]+)[）)]/g), (match) => match[1])
    .filter((note) => !["ms", "s", "毫秒", "秒", "canonical", "fallback", "可选", "历史别名"].includes(note));
  if (parenthetical.length && !specific) explanations.push(parenthetical.join("；"));
  if (field.description) explanations.push(field.description);
  if (specific) explanations.push(specific);
  if (field.requiredWhen) {
    explanations.push(field.name === "host" && field.requiredWhen === "plc4xConnectionString empty"
      ? "未配置显式连接串时必填。"
      : `条件：${field.requiredWhen}`);
  }
  return explanations.join("\n");
}
