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
        register(built, siemensS7());
        register(built, etherNetIp());
        register(built, ads());
        register(built, opcUa());
        register(built, plc4xOpcUa());
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
                        field("host", "string", "Device host", true, "127.0.0.1", null, "connection"),
                        field("port", "number", "Port", true, "502", null, "connection"),
                        field("slaveId", "number", "Slave ID", true, "1", null, "protocol"),
                        field("byteOrder", "select", "Byte order", true, "BIG_ENDIAN",
                                List.of("BIG_ENDIAN", "LITTLE_ENDIAN"), "protocol"),
                        field("parity", "select", "Parity", false, "none",
                                List.of("none", "odd", "even"), "advanced"),
                        field("plc4xConnectionString", "string", "PLC4X connection string", false, "", null, "advanced"),
                        field("pingAddress", "string", "PLC4X ping address", false, "", null, "advanced"),
                        field("maxRegistersPerRequest", "number", "Max registers per request", false, "125", null, "advanced"),
                        field("maxCoilsPerRequest", "number", "Max coils per request", false, "2000", null, "advanced"),
                        field("readTimeout", "number", "Read timeout (ms)", false, "3000", null, "advanced"),
                        field("timeout", "number", "Protocol timeout (ms)", false, "3000", null, "advanced")));
    }

    private ProtocolSchema modbusRtu() {
        return schema("MODBUS_RTU", "Modbus RTU", "Modbus serial line collection.",
                true, true, false,
                List.of("MODBUS_ASCII"),
                List.of("40001", "INPUT_REGISTER:0", "COIL:10"),
                fields(
                        field("serialPort", "string", "Serial port", true, "COM1", null, "connection"),
                        field("baudRate", "number", "Baud rate", true, "9600", null, "connection"),
                        field("dataBits", "number", "Data bits", true, "8", null, "connection"),
                        field("stopBits", "number", "Stop bits", true, "1", null, "connection"),
                        field("parity", "select", "Parity", true, "none",
                                List.of("none", "odd", "even"), "connection"),
                        field("slaveId", "number", "Slave ID", true, "1", null, "protocol"),
                        field("byteOrder", "select", "Byte order", true, "BIG_ENDIAN",
                                List.of("BIG_ENDIAN", "LITTLE_ENDIAN"), "protocol"),
                        field("interFrameDelay", "number", "Inter-frame delay (ms)", true, "5", null, "advanced"),
                        field("plc4xProtocolCode", "select", "PLC4X protocol code", false, "modbus-rtu",
                                List.of("modbus-rtu", "modbus-ascii"), "advanced"),
                        field("plc4xConnectionString", "string", "PLC4X connection string", false, "", null, "advanced"),
                        field("maxRegistersPerRequest", "number", "Max registers per request", false, "125", null, "advanced"),
                        field("maxCoilsPerRequest", "number", "Max coils per request", false, "2000", null, "advanced"),
                        field("readTimeout", "number", "Read timeout (ms)", false, "3000", null, "advanced"),
                        field("timeout", "number", "Protocol timeout (ms)", false, "3000", null, "advanced")));
    }

    private ProtocolSchema siemensS7() {
        return schema("SIEMENS_S7", "Siemens S7", "PLC4X-backed Siemens S7 read/write collector.",
                true, true, true,
                List.of("S7"),
                List.of("DB1.DBW0", "DB1.DBD4", "DB1:4:REAL", "I0.0", "Q0.0", "M10.0"),
                fields(
                        field("host", "string", "Device host", true, "127.0.0.1", null, "connection"),
                        field("port", "number", "Port", false, "102", null, "connection"),
                        field("rack", "number", "Rack", false, "0", null, "protocol"),
                        field("slot", "number", "Slot", false, "1", null, "protocol"),
                        field("controllerType", "select", "Controller type", false, "S7_1200",
                                List.of("S7_300", "S7_400", "S7_1200", "S7_1500", "LOGO"), "protocol"),
                        field("pduSize", "number", "PDU size", false, "1024", null, "advanced"),
                        field("maxFieldsPerRequest", "number", "Max fields per request", false, "64", null, "advanced"),
                        field("localTsap", "number", "Local TSAP", false, "", null, "advanced"),
                        field("remoteTsap", "number", "Remote TSAP", false, "", null, "advanced"),
                        field("localDeviceGroup", "select", "Local device group", false, "",
                                List.of("PG_OR_PC", "OS", "OTHERS"), "advanced"),
                        field("remoteDeviceGroup", "select", "Remote device group", false, "",
                                List.of("PG_OR_PC", "OS", "OTHERS"), "advanced"),
                        field("ping", "boolean", "Enable PLC4X ping", false, "false",
                                List.of("true", "false"), "advanced"),
                        field("pingTime", "number", "Ping interval (s)", false, "", null, "advanced"),
                        field("retryTime", "number", "Retry time (s)", false, "", null, "advanced"),
                        field("plc4xConnectionString", "string", "PLC4X connection string", false, "", null, "advanced"),
                        field("readTimeout", "number", "Read timeout (ms)", false, "5000", null, "advanced"),
                        field("timeout", "number", "Protocol timeout (ms)", false, "5000", null, "advanced")));
    }

    private ProtocolSchema etherNetIp() {
        return schema("ETHERNET_IP", "EtherNet/IP", "PLC4X-backed EtherNet/IP / Logix tag collector.",
                true, true, false,
                List.of("EIP", "LOGIX", "AB_ETH"),
                List.of("MainProgram.Tag1", "Program:MainProgram.Tag2", "%Tag[0]:1:DINT"),
                fields(
                        field("host", "string", "Device host", true, "127.0.0.1", null, "connection"),
                        field("port", "number", "Port", false, "44818", null, "connection"),
                        field("communicationPath", "string", "Communication path", false, "[1,0]", null, "protocol"),
                        field("backplane", "number", "Backplane", false, "1", null, "protocol"),
                        field("slot", "number", "Slot", false, "0", null, "protocol"),
                        field("maxFieldsPerRequest", "number", "Max fields per request", false, "64", null, "advanced"),
                        field("bigEndian", "boolean", "Big-endian mode", false, "true",
                                List.of("true", "false"), "advanced"),
                        field("forceUnconnectedOperation", "boolean", "Force unconnected operation", false, "false",
                                List.of("true", "false"), "advanced"),
                        field("tcpKeepAlive", "boolean", "TCP keep-alive", false, "true",
                                List.of("true", "false"), "advanced"),
                        field("tcpNoDelay", "boolean", "TCP no-delay", false, "true",
                                List.of("true", "false"), "advanced"),
                        field("plc4xConnectionString", "string", "PLC4X connection string", false, "", null, "advanced"),
                        field("readTimeout", "number", "Read timeout (ms)", false, "5000", null, "advanced"),
                        field("timeout", "number", "Protocol timeout (ms)", false, "5000", null, "advanced")));
    }

    private ProtocolSchema ads() {
        return schema("ADS", "Beckhoff ADS", "PLC4X-backed Beckhoff ADS / AMS collector.",
                true, true, true,
                List.of("AMS"),
                List.of("MAIN.temperature", "0x4020/0x0:REAL", "16416/32:STRING(80)"),
                fields(
                        field("host", "string", "Device host", true, "127.0.0.1", null, "connection"),
                        field("port", "number", "TCP port", false, "48898", null, "connection"),
                        field("targetAmsNetId", "string", "Target AMS Net ID", true, "", null, "protocol"),
                        field("targetAmsPort", "number", "Target AMS port", true, "851", null, "protocol"),
                        field("sourceAmsNetId", "string", "Source AMS Net ID", true, "", null, "protocol"),
                        field("sourceAmsPort", "number", "Source AMS port", true, "", null, "protocol"),
                        field("loadSymbolAndDataTypeTables", "boolean", "Load symbol/data type tables", false, "true",
                                List.of("true", "false"), "advanced"),
                        field("timeoutRequest", "number", "ADS request timeout (ms)", false, "4000", null, "advanced"),
                        field("maxFieldsPerRequest", "number", "Max fields per request", false, "64", null, "advanced"),
                        field("plc4xConnectionString", "string", "PLC4X connection string", false, "", null, "advanced"),
                        field("readTimeout", "number", "Read timeout (ms)", false, "5000", null, "advanced"),
                        field("timeout", "number", "Protocol timeout (ms)", false, "5000", null, "advanced")));
    }

    private ProtocolSchema opcUa() {
        return schema("OPC_UA", "OPC UA", "PLC4X-backed OPC Unified Architecture collector.",
                true, true, true,
                List.of("OPCUA"),
                List.of("ns=2;s=Channel1.Device1.Tag1", "ns=3;i=1001", "ns=3;i=1001;REAL"),
                opcUaPlc4xFields());
    }

    private ProtocolSchema plc4xOpcUa() {
        return schema("OPC_UA_PLC4X", "OPC UA (PLC4X Alias)", "Legacy alias for the PLC4X OPC UA collector kept for backward compatibility.",
                true, true, true,
                List.of("OPCUA_PLC4X"),
                List.of("ns=2;s=Channel1.Device1.Tag1", "ns=3;i=1001;REAL"),
                opcUaPlc4xFields());
    }

    private List<ProtocolFieldConfig> opcUaPlc4xFields() {
        return fields(
                field("url", "string", "Endpoint URL", false, "opc.tcp://127.0.0.1:4840", null, "connection"),
                field("endpointUrl", "string", "Endpoint URL alias", false, "opc.tcp://127.0.0.1:4840", null, "connection"),
                field("endpoint", "string", "Endpoint alias", false, "opc.tcp://127.0.0.1:4840", null, "connection"),
                field("host", "string", "Host", false, "127.0.0.1", null, "connection"),
                field("port", "number", "Port", false, "4840", null, "connection"),
                field("discovery", "boolean", "Use discovery endpoint", false, "true",
                        List.of("true", "false"), "protocol"),
                field("authType", "select", "Authentication type", false, "ANONYMOUS",
                        List.of("ANONYMOUS", "USERNAME", "CERT"), "security"),
                field("securityPolicy", "select", "Security policy", false, "NONE",
                        List.of("NONE", "Basic128Rsa15", "Basic256", "Basic256Sha256",
                                "Aes128_Sha256_RsaOaep", "Aes256_Sha256_RsaPss"),
                        "security"),
                field("messageSecurity", "select", "Message security", false, "NONE",
                        List.of("NONE", "SIGN", "SIGN_ENCRYPT"), "security"),
                field("securityMode", "select", "Security mode alias", false, "NONE",
                        List.of("NONE", "Sign", "SignAndEncrypt"), "security"),
                conditional("username", "string", "Username", false, "", null, "security", "authType=USERNAME"),
                field("password", "password", "Password", false, "", null, "security"),
                field("authParams", "object", "Authentication params alias", false, "{}", null, "security"),
                field("keyStoreFile", "string", "Client key store file", false, "", null, "security"),
                field("keyStoreType", "string", "Client key store type", false, "pkcs12", null, "security"),
                field("keyStorePassword", "password", "Client key store password", false, "", null, "security"),
                conditional("clientCertPath", "string", "Client certificate alias", false, "", null,
                        "security", "authType=CERT or securityPolicy!=NONE"),
                field("clientCertPassword", "password", "Client certificate password alias", false, "", null, "security"),
                field("trustStoreFile", "string", "Trust store file", false, "", null, "security"),
                field("trustStoreType", "string", "Trust store type", false, "pkcs12", null, "security"),
                field("trustStorePassword", "password", "Trust store password", false, "", null, "security"),
                field("serverCertificateFile", "string", "Pinned server certificate file", false, "", null, "security"),
                field("endpointHost", "string", "Endpoint host override", false, "", null, "advanced"),
                field("endpointPort", "number", "Endpoint port override", false, "", null, "advanced"),
                field("channelLifetime", "number", "Secure channel lifetime (ms)", false, "3600000", null, "advanced"),
                field("sessionTimeout", "number", "Session timeout (ms)", false, "120000", null, "advanced"),
                field("negotiationTimeout", "number", "Negotiation timeout (ms)", false, "60000", null, "advanced"),
                field("connectTimeoutMs", "number", "Connect timeout alias (ms)", false, "60000", null, "advanced"),
                field("connectTimeout", "number", "Connect timeout alias (ms)", false, "60000", null, "advanced"),
                field("requestTimeout", "number", "Request timeout (ms)", false, "30000", null, "advanced"),
                field("requestTimeoutMs", "number", "Request timeout alias (ms)", false, "30000", null, "advanced"),
                field("subscriptionInterval", "number", "Subscription interval (ms)", false, "1000", null, "advanced"),
                field("maxFieldsPerRequest", "number", "Max fields per request", false, "100", null, "advanced"),
                field("plc4xConnectionString", "string", "PLC4X connection string", false, "", null, "advanced"));
    }

    private ProtocolSchema opcDa() {
        return schema("OPC_DA", "OPC DA", "OPC DA access through local or bridge mode.",
                true, true, true,
                List.of(),
                List.of("Channel1.Device1.Tag1", "Random.Real8"),
                fields(
                        field("host", "string", "Host", true, "127.0.0.1", null, "connection"),
                        field("serverProgId", "string", "Server ProgID", true, "Matrikon.OPC.Simulation.1", null, "connection"),
                        field("progId", "string", "ProgID alias", false, "Matrikon.OPC.Simulation.1", null, "connection"),
                        field("clsid", "string", "CLSID alias", false, "", null, "connection"),
                        field("bridgeMode", "select", "Bridge mode", true, "INMEMORY",
                                List.of("INMEMORY", "HTTP"), "bridge"),
                        conditional("bridgeBaseUrl", "string", "Bridge base URL", false,
                                "http://127.0.0.1:18080/api/v1/opcda", null, "bridge", "bridgeMode=HTTP"),
                        field("url", "string", "Bridge or access URL", false,
                                "http://127.0.0.1:18080/api/v1/opcda", null, "bridge"),
                        field("bridgeToken", "password", "Bridge token", false, "", null, "bridge"),
                        field("bridgeRetryCount", "number", "Bridge retry count", false, "1", null, "advanced"),
                        field("bridgeRetryBackoffMs", "number", "Bridge retry backoff (ms)", false, "200", null, "advanced"),
                        field("username", "string", "Username", false, "", null, "security"),
                        field("password", "password", "Password", false, "", null, "security"),
                        field("domain", "string", "Windows domain", false, "", null, "security"),
                        field("requestTimeout", "number", "Request timeout (ms)", false, "5000", null, "advanced"),
                        field("updateRate", "number", "Subscription refresh interval (ms)", false, "1000", null, "advanced")));
    }

    private ProtocolSchema iec104() {
        return schema("IEC104", "IEC 60870-5-104", "IEC104 telemetry collection.",
                true, false, false,
                List.of("IEC_104"),
                List.of("M_SP_NA_1:1", "M_ME_NC_1:100"),
                fields(
                        field("host", "string", "Device host", true, "127.0.0.1", null, "connection"),
                        field("port", "number", "Port", true, "2404", null, "connection"),
                        field("slaveId", "number", "Common address", true, "1", null, "protocol"),
                        field("timeout", "number", "Protocol timeout (ms)", true, "5000", null, "advanced")));
    }

    private ProtocolSchema iec61850() {
        return schema("IEC61850", "IEC 61850", "IEC61850 MMS collection.",
                true, false, false,
                List.of("IEC_61850"),
                List.of("LD0/MMXU1.A.phsA.cVal.mag.f"),
                fields(
                        field("host", "string", "Device host", true, "127.0.0.1", null, "connection"),
                        field("port", "number", "MMS port", true, "102", null, "connection"),
                        field("timeout", "number", "Protocol timeout (ms)", true, "10000", null, "advanced"),
                        field("readTimeout", "number", "Read timeout (ms)", false, "10000", null, "advanced")));
    }

    private ProtocolSchema mqtt() {
        return schema("MQTT", "MQTT", "MQTT subscription/publish collection protocol.",
                true, true, true,
                List.of("MQTT_SSL"),
                List.of("devices/${deviceId}/temperature", "factory/line1/+/status"),
                fields(
                        field("url", "string", "Broker URL", false, "tcp://127.0.0.1:1883", null, "connection"),
                        field("brokerUrl", "string", "Broker URL alias", false, "tcp://127.0.0.1:1883", null, "connection"),
                        field("host", "string", "Broker host", false, "127.0.0.1", null, "connection"),
                        field("port", "number", "Broker port", false, "1883", null, "connection"),
                        field("clientId", "string", "Client ID", true, "device_mqtt", null, "connection"),
                        field("version", "select", "MQTT version", true, "v5", List.of("v5", "v3"), "connection"),
                        field("username", "string", "Username", false, "", null, "security"),
                        field("password", "password", "Password", false, "", null, "security"),
                        field("sslEnabled", "boolean", "Enable SSL", false, "false",
                                List.of("true", "false"), "security"),
                        field("subscribeTopics", "string", "Default subscribe topics", false,
                                "devices/${deviceId}/#", null, "topic"),
                        field("subscribeQos", "select", "Default subscribe QoS", false, "1",
                                List.of("0", "1", "2"), "topic"),
                        field("publishTopic", "string", "Default publish topic", false,
                                "devices/${deviceId}/data", null, "topic"),
                        field("publishQos", "select", "Publish QoS", false, "1",
                                List.of("0", "1", "2"), "topic"),
                        field("retained", "boolean", "Retained publish flag", false, "false",
                                List.of("true", "false"), "topic"),
                        field("cleanSession", "boolean", "Clean session", false, "true",
                                List.of("true", "false"), "advanced"),
                        field("autoReconnect", "boolean", "Auto reconnect", false, "true",
                                List.of("true", "false"), "advanced"),
                        field("connectTimeout", "number", "Connect timeout (ms)", false, "10000", null, "advanced"),
                        field("heartbeatInterval", "number", "Heartbeat interval (ms)", false, "60000", null, "advanced"),
                        field("overflowStrategy", "select", "Overflow strategy", false, "BLOCK",
                                List.of("BLOCK", "DROP_LATEST", "DROP_OLDEST"), "advanced")));
    }

    private ProtocolSchema snmp() {
        return schema("SNMP", "SNMP", "SNMP polling protocol.",
                true, false, false,
                List.of("SNMP_V1", "SNMP_V2C", "SNMP_V3"),
                List.of("1.3.6.1.2.1.1.3.0", "1.3.6.1.4.1.2021.10.1.3.1"),
                fields(
                        field("host", "string", "Device host", true, "127.0.0.1", null, "connection"),
                        field("port", "number", "Port", true, "161", null, "connection"),
                        field("community", "string", "Community", true, "public", null, "security"),
                        field("snmpVersion", "select", "SNMP version", true, "2c",
                                List.of("1", "2c", "3"), "protocol"),
                        conditional("snmpSecurityName", "string", "SNMPv3 security name", false, "", null,
                                "security", "snmpVersion=3"),
                        conditional("snmpSecurityLevel", "select", "SNMPv3 security level", false, "authPriv",
                                List.of("noAuthNoPriv", "authNoPriv", "authPriv"), "security", "snmpVersion=3"),
                        conditional("snmpAuthProtocol", "select", "SNMPv3 auth protocol", false, "SHA",
                                List.of("MD5", "SHA", "SHA224", "SHA256", "SHA384", "SHA512", "NONE"),
                                "security", "snmpSecurityLevel=authNoPriv/authPriv"),
                        conditional("snmpAuthPassword", "password", "SNMPv3 auth password", false, "", null,
                                "security", "snmpSecurityLevel=authNoPriv/authPriv"),
                        conditional("snmpPrivProtocol", "select", "SNMPv3 privacy protocol", false, "AES128",
                                List.of("DES", "AES128", "AES192", "AES256", "NONE"),
                                "security", "snmpSecurityLevel=authPriv"),
                        conditional("snmpPrivPassword", "password", "SNMPv3 privacy password", false, "", null,
                                "security", "snmpSecurityLevel=authPriv"),
                        field("readTimeout", "number", "Read timeout (ms)", false, "5000", null, "advanced"),
                        field("timeout", "number", "Protocol timeout (ms)", false, "5000", null, "advanced"),
                        field("snmpRetries", "number", "Retry count", false, "1", null, "advanced")));
    }

    private ProtocolSchema coap() {
        return schema("COAP", "CoAP", "CoAP request/response collection protocol.",
                true, true, false,
                List.of("COAP_SSL"),
                List.of("/sensors/temp", "coap://device.local/sensors/humidity"),
                fields(
                        field("url", "string", "CoAP base URL", false, "coap://127.0.0.1:5683", null, "connection"),
                        field("host", "string", "Device host", false, "127.0.0.1", null, "connection"),
                        field("port", "number", "Port", false, "5683", null, "connection"),
                        field("scheme", "select", "Scheme", false, "coap", List.of("coap", "coaps"), "connection"),
                        field("readTimeout", "number", "Read timeout (ms)", false, "5000", null, "advanced"),
                        field("timeout", "number", "Protocol timeout (ms)", false, "3000", null, "advanced"),
                        field("overflowStrategy", "select", "Overflow strategy", false, "BLOCK",
                                List.of("BLOCK", "DROP_LATEST", "DROP_OLDEST"), "advanced")));
    }

    private ProtocolSchema http() {
        return schema("HTTP", "HTTP", "HTTP polling and request based collection.",
                true, true, false,
                List.of("HTTPS"),
                List.of("/api/data", "http://device.local/status"),
                fields(
                        field("url", "string", "HTTP base URL", false, "http://127.0.0.1:8080", null, "connection"),
                        field("host", "string", "Host", false, "127.0.0.1", null, "connection"),
                        field("port", "number", "Port", false, "8080", null, "connection"),
                        field("sslEnabled", "boolean", "Enable HTTPS", false, "false",
                                List.of("true", "false"), "security"),
                        field("path", "string", "Base path", false, "", null, "request"),
                        field("method", "select", "Request method", false, "POST",
                                List.of("GET", "POST", "PUT", "DELETE", "HEAD"), "request"),
                        field("headers", "object", "Request headers", false, "{}", null, "request"),
                        field("queryParams", "object", "Query parameters", false, "{}", null, "request"),
                        field("sendEndpoint", "string", "Send endpoint", false, "/api/data", null, "request"),
                        field("receiveEndpoint", "string", "Receive endpoint", false, "/api/receive", null, "request"),
                        field("healthCheckPath", "string", "Health check path", false, "/health", null, "advanced"),
                        field("username", "string", "Username", false, "", null, "security"),
                        field("password", "password", "Password", false, "", null, "security"),
                        field("authToken", "password", "Bearer token", false, "", null, "security"),
                        field("connectTimeout", "number", "Connect timeout (ms)", false, "10000", null, "advanced"),
                        field("readTimeout", "number", "Read timeout (ms)", false, "5000", null, "advanced")));
    }

    private ProtocolSchema websocket() {
        return schema("WEBSOCKET", "WebSocket", "WebSocket collection protocol.",
                true, true, true,
                List.of("WEBSOCKET_SSL"),
                List.of("ws://127.0.0.1:8080/ws", "/ws/device"),
                fields(
                        field("url", "string", "WebSocket URL", false, "ws://127.0.0.1:8080/ws", null, "connection"),
                        field("host", "string", "Host", false, "127.0.0.1", null, "connection"),
                        field("port", "number", "Port", false, "8080", null, "connection"),
                        field("sslEnabled", "boolean", "Enable WSS", false, "false",
                                List.of("true", "false"), "security"),
                        field("path", "string", "Connect path", false, "/ws", null, "connection"),
                        field("headers", "object", "Request headers", false, "{}", null, "request"),
                        field("queryParams", "object", "Query parameters", false, "{}", null, "request"),
                        field("username", "string", "Username", false, "", null, "security"),
                        field("password", "password", "Password", false, "", null, "security"),
                        field("authToken", "password", "Bearer token", false, "", null, "security"),
                        field("subprotocol", "string", "Subprotocol", false, "collector-v1", null, "advanced"),
                        field("binaryMode", "boolean", "Binary mode", false, "false",
                                List.of("true", "false"), "advanced"),
                        field("heartbeatInterval", "number", "Heartbeat interval (ms)", false, "60000", null, "advanced"),
                        field("heartbeatMessage", "string", "Heartbeat message", false, "ping", null, "advanced"),
                        field("heartbeatUsePing", "boolean", "Use ping frame", false, "true",
                                List.of("true", "false"), "advanced")));
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
