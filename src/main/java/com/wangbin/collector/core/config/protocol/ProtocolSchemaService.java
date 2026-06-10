package com.wangbin.collector.core.config.protocol;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Central protocol metadata registry for the admin UI.
 */
@Service
public class ProtocolSchemaService {

    private static final List<String> COMMON_DATA_TYPES = List.of(
            "INT", "FLOAT", "DOUBLE", "BOOLEAN", "STRING", "BYTE", "SHORT", "LONG", "UINT16", "UINT32");

    private final Map<String, ProtocolSchema> schemas;
    private final Map<String, String> aliases;

    public ProtocolSchemaService() {
        LinkedHashMap<String, ProtocolSchema> built = new LinkedHashMap<>();
        register(built, modbusTcp());
        register(built, modbusRtu());
        register(built, opcUa());
        register(built, opcDa());
        register(built, iec104());
        register(built, iec61850());
        register(built, mqtt());
        register(built, snmp());
        register(built, coap());
        register(built, http());
        register(built, websocket());
        register(built, customTcp());
        this.schemas = Collections.unmodifiableMap(built);

        LinkedHashMap<String, String> aliasMap = new LinkedHashMap<>();
        built.values().forEach(schema -> {
            aliasMap.put(normalize(schema.getProtocol()), schema.getProtocol());
            schema.getAliases().forEach(alias -> aliasMap.put(normalize(alias), schema.getProtocol()));
        });
        this.aliases = Collections.unmodifiableMap(aliasMap);
    }

    public List<ProtocolSchema> getAllSchemas() {
        return new ArrayList<>(schemas.values());
    }

    public Optional<ProtocolSchema> getSchema(String protocol) {
        String canonical = aliases.get(normalize(protocol));
        return canonical == null ? Optional.empty() : Optional.ofNullable(schemas.get(canonical));
    }

    public List<ProtocolFieldConfig> getConnectionFields(String protocol) {
        return getSchema(protocol)
                .map(ProtocolSchema::getConnectionFields)
                .orElseGet(Collections::emptyList);
    }

    private void register(Map<String, ProtocolSchema> target, ProtocolSchema schema) {
        target.put(schema.getProtocol(), schema);
    }

    private ProtocolSchema modbusTcp() {
        return schema("MODBUS_TCP", "Modbus TCP", "Modbus TCP register polling over Ethernet.",
                true, true, false,
                List.of(),
                List.of("40001", "HOLDING_REGISTER:1", "COIL:0"),
                fields(
                        field("host", "string", "设备 IP", true, "127.0.0.1", null, "connection"),
                        field("port", "number", "端口", true, "502", null, "connection"),
                        field("slaveId", "number", "从站 ID", true, "1", null, "protocol"),
                        field("byteOrder", "select", "字节顺序", true, "BIG_ENDIAN", List.of("BIG_ENDIAN", "LITTLE_ENDIAN"), "protocol"),
                        field("parity", "select", "校验位", false, "none", List.of("none", "odd", "even"), "advanced"),
                        field("readTimeout", "number", "读取超时(ms)", false, "3000", null, "advanced"),
                        field("timeout", "number", "协议超时(ms)", false, "3000", null, "advanced")));
    }

    private ProtocolSchema modbusRtu() {
        return schema("MODBUS_RTU", "Modbus RTU", "Modbus serial line collection.",
                true, true, false,
                List.of("MODBUS_ASCII"),
                List.of("40001", "INPUT_REGISTER:0", "COIL:10"),
                fields(
                        field("serialPort", "string", "串口", true, "COM1", null, "connection"),
                        field("baudRate", "number", "波特率", true, "9600", null, "connection"),
                        field("dataBits", "number", "数据位", true, "8", null, "connection"),
                        field("stopBits", "number", "停止位", true, "1", null, "connection"),
                        field("parity", "select", "校验位", true, "none", List.of("none", "odd", "even"), "connection"),
                        field("slaveId", "number", "从站 ID", true, "1", null, "protocol"),
                        field("byteOrder", "select", "字节顺序", true, "BIG_ENDIAN", List.of("BIG_ENDIAN", "LITTLE_ENDIAN"), "protocol"),
                        field("interFrameDelay", "number", "帧间延时(ms)", true, "5", null, "advanced"),
                        field("readTimeout", "number", "读取超时(ms)", false, "3000", null, "advanced"),
                        field("timeout", "number", "协议超时(ms)", false, "3000", null, "advanced")));
    }

    private ProtocolSchema opcUa() {
        return schema("OPC_UA", "OPC UA", "OPC Unified Architecture client.",
                true, true, true,
                List.of("OPCUA"),
                List.of("ns=2;s=Channel1.Device1.Tag1", "ns=3;i=1001"),
                fields(
                        field("url", "string", "OPC UA 端点地址", false, "opc.tcp://127.0.0.1:4840", null, "connection"),
                        field("endpointUrl", "string", "端点地址兼容字段", false, "opc.tcp://127.0.0.1:4840", null, "connection"),
                        field("endpoint", "string", "端点别名", false, "opc.tcp://127.0.0.1:4840", null, "connection"),
                        field("host", "string", "主机", false, "127.0.0.1", null, "connection"),
                        field("port", "number", "端口", false, "4840", null, "connection"),
                        field("securityPolicy", "select", "安全策略", true, "None",
                                List.of("None", "Basic128Rsa15", "Basic256", "Basic256Sha256", "Aes128_Sha256_RsaOaep", "Aes256_Sha256_RsaPss"), "security"),
                        field("securityMode", "select", "安全模式", true, "None", List.of("None", "Sign", "SignAndEncrypt"), "security"),
                        field("authType", "select", "认证方式", true, "ANONYMOUS", List.of("ANONYMOUS", "USERNAME", "CERT"), "security"),
                        conditional("username", "string", "用户名", false, "", null, "security", "authType=USERNAME"),
                        field("password", "password", "密码", false, "", null, "security"),
                        conditional("clientCertPath", "string", "客户端证书路径", false, "", null, "security", "authType=CERT or securityPolicy!=None"),
                        field("clientCertPassword", "password", "客户端证书密码", false, "", null, "security"),
                        field("trustAllServerCert", "boolean", "信任所有服务端证书", false, "false", List.of("true", "false"), "security"),
                        field("requestTimeout", "number", "请求超时(ms)", false, "5000", null, "advanced"),
                        field("connectTimeout", "number", "连接超时(ms)", false, "5000", null, "advanced"),
                        field("subscriptionInterval", "number", "订阅发布间隔(ms)", false, "1000", null, "advanced"),
                        field("namespaceUri", "string", "命名空间 URI", false, "", null, "advanced")));
    }

    private ProtocolSchema opcDa() {
        return schema("OPC_DA", "OPC DA", "OPC DA access through local or bridge mode.",
                true, true, true,
                List.of(),
                List.of("Channel1.Device1.Tag1", "Random.Real8"),
                fields(
                        field("host", "string", "OPC DA 主机", true, "127.0.0.1", null, "connection"),
                        field("serverProgId", "string", "服务 ProgID", true, "Matrikon.OPC.Simulation.1", null, "connection"),
                        field("progId", "string", "ProgID 兼容字段", false, "Matrikon.OPC.Simulation.1", null, "connection"),
                        field("clsid", "string", "CLSID 兼容字段", false, "", null, "connection"),
                        field("bridgeMode", "select", "桥接模式", true, "INMEMORY", List.of("INMEMORY", "HTTP"), "bridge"),
                        conditional("bridgeBaseUrl", "string", "桥接基础地址", false, "http://127.0.0.1:18080/api/v1/opcda", null, "bridge", "bridgeMode=HTTP"),
                        field("url", "string", "桥接地址或访问地址", false, "http://127.0.0.1:18080/api/v1/opcda", null, "bridge"),
                        field("bridgeToken", "password", "桥接访问令牌", false, "", null, "bridge"),
                        field("bridgeRetryCount", "number", "桥接重试次数", false, "1", null, "advanced"),
                        field("bridgeRetryBackoffMs", "number", "桥接重试退避(ms)", false, "200", null, "advanced"),
                        field("username", "string", "用户名", false, "", null, "security"),
                        field("password", "password", "密码", false, "", null, "security"),
                        field("domain", "string", "Windows 域", false, "", null, "security"),
                        field("requestTimeout", "number", "请求超时(ms)", false, "5000", null, "advanced"),
                        field("updateRate", "number", "订阅刷新周期(ms)", false, "1000", null, "advanced")));
    }

    private ProtocolSchema iec104() {
        return schema("IEC104", "IEC 60870-5-104", "IEC104 telemetry collection.",
                true, false, false,
                List.of("IEC_104"),
                List.of("M_SP_NA_1:1", "M_ME_NC_1:100"),
                fields(
                        field("host", "string", "设备 IP", true, "127.0.0.1", null, "connection"),
                        field("port", "number", "端口", true, "2404", null, "connection"),
                        field("slaveId", "number", "公共地址(commonAddress)", true, "1", null, "protocol"),
                        field("timeout", "number", "协议超时(ms)", true, "5000", null, "advanced")));
    }

    private ProtocolSchema iec61850() {
        return schema("IEC61850", "IEC 61850", "IEC61850 MMS collection.",
                true, false, false,
                List.of("IEC_61850"),
                List.of("LD0/MMXU1.A.phsA.cVal.mag.f"),
                fields(
                        field("host", "string", "设备 IP", true, "127.0.0.1", null, "connection"),
                        field("port", "number", "MMS 端口", true, "102", null, "connection"),
                        field("timeout", "number", "协议超时(ms)", true, "10000", null, "advanced"),
                        field("readTimeout", "number", "读取超时(ms)", false, "10000", null, "advanced")));
    }

    private ProtocolSchema mqtt() {
        return schema("MQTT", "MQTT", "MQTT subscription/publish collection protocol.",
                true, true, true,
                List.of("MQTT_SSL"),
                List.of("devices/${deviceId}/temperature", "factory/line1/+/status"),
                fields(
                        field("url", "string", "Broker 完整地址", false, "tcp://127.0.0.1:1883", null, "connection"),
                        field("brokerUrl", "string", "Broker 地址兼容字段", false, "tcp://127.0.0.1:1883", null, "connection"),
                        field("host", "string", "Broker 主机", false, "127.0.0.1", null, "connection"),
                        field("port", "number", "Broker 端口", false, "1883", null, "connection"),
                        field("clientId", "string", "客户端 ID", true, "device_mqtt", null, "connection"),
                        field("version", "select", "MQTT 版本", true, "v5", List.of("v5", "v3"), "connection"),
                        field("username", "string", "用户名", false, "", null, "security"),
                        field("password", "password", "密码", false, "", null, "security"),
                        field("sslEnabled", "boolean", "启用 SSL", false, "false", List.of("true", "false"), "security"),
                        field("subscribeTopics", "string", "默认订阅主题列表", false, "devices/${deviceId}/#", null, "topic"),
                        field("subscribeQos", "select", "默认订阅 QoS", false, "1", List.of("0", "1", "2"), "topic"),
                        field("publishTopic", "string", "默认发布主题", false, "devices/${deviceId}/data", null, "topic"),
                        field("publishQos", "select", "发布 QoS", false, "1", List.of("0", "1", "2"), "topic"),
                        field("retained", "boolean", "发布保留标记", false, "false", List.of("true", "false"), "topic"),
                        field("cleanSession", "boolean", "清理会话", false, "true", List.of("true", "false"), "advanced"),
                        field("autoReconnect", "boolean", "自动重连", false, "true", List.of("true", "false"), "advanced"),
                        field("connectTimeout", "number", "连接超时(ms)", false, "10000", null, "advanced"),
                        field("heartbeatInterval", "number", "保活间隔(ms)", false, "60000", null, "advanced"),
                        field("overflowStrategy", "select", "消息溢出策略", false, "BLOCK", List.of("BLOCK", "DROP_LATEST", "DROP_OLDEST"), "advanced")));
    }

    private ProtocolSchema snmp() {
        return schema("SNMP", "SNMP", "SNMP polling protocol.",
                true, false, false,
                List.of("SNMP_V1", "SNMP_V2C", "SNMP_V3"),
                List.of("1.3.6.1.2.1.1.3.0", "1.3.6.1.4.1.2021.10.1.3.1"),
                fields(
                        field("host", "string", "设备 IP", true, "127.0.0.1", null, "connection"),
                        field("port", "number", "端口", true, "161", null, "connection"),
                        field("community", "string", "团体字", true, "public", null, "security"),
                        field("snmpVersion", "select", "SNMP 版本", true, "2c", List.of("1", "2c", "3"), "protocol"),
                        conditional("snmpSecurityName", "string", "SNMPv3 安全用户名", false, "", null, "security", "snmpVersion=3"),
                        conditional("snmpSecurityLevel", "select", "SNMPv3 安全级别", false, "authPriv", List.of("noAuthNoPriv", "authNoPriv", "authPriv"), "security", "snmpVersion=3"),
                        conditional("snmpAuthProtocol", "select", "SNMPv3 认证协议", false, "SHA", List.of("MD5", "SHA", "SHA224", "SHA256", "SHA384", "SHA512", "NONE"), "security", "snmpSecurityLevel=authNoPriv/authPriv"),
                        conditional("snmpAuthPassword", "password", "SNMPv3 认证密码", false, "", null, "security", "snmpSecurityLevel=authNoPriv/authPriv"),
                        conditional("snmpPrivProtocol", "select", "SNMPv3 加密协议", false, "AES128", List.of("DES", "AES128", "AES192", "AES256", "NONE"), "security", "snmpSecurityLevel=authPriv"),
                        conditional("snmpPrivPassword", "password", "SNMPv3 加密密码", false, "", null, "security", "snmpSecurityLevel=authPriv"),
                        field("readTimeout", "number", "读取超时(ms)", false, "5000", null, "advanced"),
                        field("timeout", "number", "协议超时(ms)", false, "5000", null, "advanced"),
                        field("snmpRetries", "number", "重试次数", false, "1", null, "advanced")));
    }

    private ProtocolSchema coap() {
        return schema("COAP", "CoAP", "CoAP request/response collection protocol.",
                true, true, false,
                List.of("COAP_SSL"),
                List.of("/sensors/temp", "coap://device.local/sensors/humidity"),
                fields(
                        field("url", "string", "CoAP 基础地址", false, "coap://127.0.0.1:5683", null, "connection"),
                        field("host", "string", "设备 IP", false, "127.0.0.1", null, "connection"),
                        field("port", "number", "端口", false, "5683", null, "connection"),
                        field("scheme", "select", "协议方案", false, "coap", List.of("coap", "coaps"), "connection"),
                        field("readTimeout", "number", "读取超时(ms)", false, "5000", null, "advanced"),
                        field("timeout", "number", "协议超时(ms)", false, "3000", null, "advanced"),
                        field("overflowStrategy", "select", "请求溢出策略", false, "BLOCK", List.of("BLOCK", "DROP_LATEST", "DROP_OLDEST"), "advanced")));
    }

    private ProtocolSchema http() {
        return schema("HTTP", "HTTP", "HTTP polling and request based collection.",
                true, true, false,
                List.of("HTTPS"),
                List.of("/api/data", "http://device.local/status"),
                fields(
                        field("url", "string", "HTTP 基础地址", false, "http://127.0.0.1:8080", null, "connection"),
                        field("host", "string", "主机", false, "127.0.0.1", null, "connection"),
                        field("port", "number", "端口", false, "8080", null, "connection"),
                        field("sslEnabled", "boolean", "启用 HTTPS", false, "false", List.of("true", "false"), "security"),
                        field("path", "string", "基础路径", false, "", null, "request"),
                        field("method", "select", "发送方法", false, "POST", List.of("GET", "POST", "PUT", "DELETE", "HEAD"), "request"),
                        field("headers", "object", "自定义请求头", false, "{}", null, "request"),
                        field("queryParams", "object", "查询参数", false, "{}", null, "request"),
                        field("sendEndpoint", "string", "发送接口路径", false, "/api/data", null, "request"),
                        field("receiveEndpoint", "string", "接收接口路径", false, "/api/receive", null, "request"),
                        field("healthCheckPath", "string", "健康检查路径", false, "/health", null, "advanced"),
                        field("username", "string", "用户名", false, "", null, "security"),
                        field("password", "password", "密码", false, "", null, "security"),
                        field("authToken", "password", "Bearer 令牌", false, "", null, "security"),
                        field("connectTimeout", "number", "连接超时(ms)", false, "10000", null, "advanced"),
                        field("readTimeout", "number", "读取超时(ms)", false, "5000", null, "advanced")));
    }

    private ProtocolSchema websocket() {
        return schema("WEBSOCKET", "WebSocket", "WebSocket collection protocol.",
                true, true, true,
                List.of("WEBSOCKET_SSL"),
                List.of("ws://127.0.0.1:8080/ws", "/ws/device"),
                fields(
                        field("url", "string", "WebSocket 地址", false, "ws://127.0.0.1:8080/ws", null, "connection"),
                        field("host", "string", "主机", false, "127.0.0.1", null, "connection"),
                        field("port", "number", "端口", false, "8080", null, "connection"),
                        field("sslEnabled", "boolean", "启用 WSS", false, "false", List.of("true", "false"), "security"),
                        field("path", "string", "连接路径", false, "/ws", null, "connection"),
                        field("headers", "object", "自定义请求头", false, "{}", null, "request"),
                        field("queryParams", "object", "查询参数", false, "{}", null, "request"),
                        field("username", "string", "用户名", false, "", null, "security"),
                        field("password", "password", "密码", false, "", null, "security"),
                        field("authToken", "password", "Bearer 令牌", false, "", null, "security"),
                        field("subprotocol", "string", "子协议", false, "collector-v1", null, "advanced"),
                        field("binaryMode", "boolean", "二进制收发", false, "false", List.of("true", "false"), "advanced"),
                        field("heartbeatInterval", "number", "心跳间隔(ms)", false, "60000", null, "advanced"),
                        field("heartbeatMessage", "string", "心跳消息", false, "ping", null, "advanced"),
                        field("heartbeatUsePing", "boolean", "使用 Ping 帧", false, "true", List.of("true", "false"), "advanced")));
    }

    private ProtocolSchema customTcp() {
        return schema("CUSTOM_TCP", "Custom TCP", "Placeholder custom protocol. Real collection is not implemented yet.",
                false, false, false,
                List.of("TCP", "CUSTOM_UDP"),
                List.of(),
                fields());
    }

    private ProtocolSchema schema(String protocol,
                                  String title,
                                  String description,
                                  boolean implemented,
                                  boolean writable,
                                  boolean subscribable,
                                  List<String> aliases,
                                  List<String> pointAddressHints,
                                  List<ProtocolFieldConfig> fields) {
        return ProtocolSchema.builder()
                .protocol(protocol)
                .title(title)
                .description(description)
                .implemented(implemented)
                .writable(writable)
                .subscribable(subscribable)
                .aliases(aliases)
                .pointAddressHints(pointAddressHints)
                .dataTypes(COMMON_DATA_TYPES)
                .connectionFields(fields)
                .build();
    }

    private List<ProtocolFieldConfig> fields(ProtocolFieldConfig... fields) {
        return Arrays.asList(fields);
    }

    private ProtocolFieldConfig field(String name,
                                      String type,
                                      String label,
                                      boolean required,
                                      String defaultValue,
                                      List<String> options,
                                      String group) {
        return conditional(name, type, label, required, defaultValue, options, group, null);
    }

    private ProtocolFieldConfig conditional(String name,
                                            String type,
                                            String label,
                                            boolean required,
                                            String defaultValue,
                                            List<String> options,
                                            String group,
                                            String requiredWhen) {
        return ProtocolFieldConfig.builder()
                .name(name)
                .type(type)
                .label(label)
                .required(required)
                .defaultValue(defaultValue)
                .options(options == null ? Collections.emptyList() : options)
                .group(group)
                .requiredWhen(requiredWhen)
                .build();
    }

    private String normalize(String protocol) {
        if (protocol == null) {
            return "";
        }
        return protocol.trim().toUpperCase(Locale.ROOT).replace("-", "_");
    }
}
