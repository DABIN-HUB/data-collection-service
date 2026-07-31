package com.wangbin.collector.common.domain.enums;

/**
 * 支持的采集协议类型。
 */
public enum ProtocolType {

    // Modbus 协议族。
    MODBUS_TCP("MODBUS_TCP", "Modbus TCP", 502),
    MODBUS_RTU("MODBUS_RTU", "Modbus RTU", null),
    MODBUS_ASCII("MODBUS_ASCII", "Modbus ASCII", null),
    SIEMENS_S7("SIEMENS_S7", "Siemens S7", 102),
    BACNET_IP("BACNET_IP", "BACnet/IP", 47808),
    BACNET_MSTP("BACNET_MSTP", "BACnet MS/TP", null),
    BACNET_SC("BACNET_SC", "BACnet/SC", 443),
    ETHERNET_IP("ETHERNET_IP", "EtherNet/IP", 44818),
    ADS("ADS", "Beckhoff ADS", 48898),
    KNXNET_IP("KNXNET_IP", "KNXnet/IP", 3671),

    // OPC 协议族。
    OPC_DA("OPC_DA", "OPC DA", null),
    OPC_UA("OPC_UA", "OPC UA", 4840),
    OPC_UA_PLC4X("OPC_UA_PLC4X", "OPC UA (PLC4X Alias)", 4840),

    // SNMP 协议族。
    SNMP_V1("SNMP_V1", "SNMP v1", 161),
    SNMP_V2C("SNMP_V2C", "SNMP v2c", 161),
    SNMP_V3("SNMP_V3", "SNMP v3", 161),

    // MQTT 协议族。
    MQTT("MQTT", "MQTT", 1883),
    MQTT_SSL("MQTT_SSL", "MQTT SSL", 8883),

    // CoAP 协议族。
    COAP("COAP", "CoAP", 5683),
    COAP_SSL("COAP_SSL", "CoAP SSL", 5684),

    // IEC 协议族。
    IEC104("IEC104", "IEC 60870-5-104", 2404),
    IEC61850("IEC61850", "IEC 61850", 102),

    // HTTP 协议族。
    HTTP("HTTP", "HTTP", 80),
    HTTPS("HTTPS", "HTTPS", 443),

    // WebSocket 协议族。
    WEBSOCKET("WEBSOCKET", "WebSocket", 80),
    WEBSOCKET_SSL("WEBSOCKET_SSL", "WebSocket SSL", 443),

    // 自定义协议族。
    CUSTOM_TCP("CUSTOM_TCP", "Custom TCP", null),
    CUSTOM_UDP("CUSTOM_UDP", "Custom UDP", null);

    private final String code;
    private final String description;
    private final Integer defaultPort;

    /**
     * 创建当前组件实例。
     */
    ProtocolType(String code, String description, Integer defaultPort) {
        this.code = code;
        this.description = description;
        this.defaultPort = defaultPort;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public Integer getDefaultPort() {
        return defaultPort;
    }

    /**
     * 创建并返回业务对象。
     */
    public static ProtocolType fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (ProtocolType type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }

    public boolean isTcpProtocol() {
        return this == MODBUS_TCP
                || this == SIEMENS_S7
                || this == ETHERNET_IP
                || this == ADS
                || this == OPC_UA
                || this == OPC_UA_PLC4X
                || this == IEC104
                || this == HTTP
                || this == HTTPS
                || this == WEBSOCKET
                || this == WEBSOCKET_SSL
                || this == CUSTOM_TCP
                || this == BACNET_SC;
    }

    public boolean isSerialProtocol() {
        return this == MODBUS_RTU || this == MODBUS_ASCII || this == BACNET_MSTP;
    }

    /**
     * 执行当前业务逻辑。
     */
    public boolean needEncryption() {
        return this == HTTPS
                || this == MQTT_SSL
                || this == COAP_SSL
                || this == WEBSOCKET_SSL
                || this == BACNET_SC;
    }

    public int getDefaultTimeout() {
        return switch (this) {
            case MODBUS_TCP, MODBUS_RTU, MODBUS_ASCII -> 3000;
            case BACNET_IP, BACNET_MSTP, BACNET_SC -> 5000;
            case SIEMENS_S7, ETHERNET_IP, ADS -> 5000;
            case KNXNET_IP -> 10000;
            case OPC_UA, OPC_UA_PLC4X -> 10000;
            case SNMP_V1, SNMP_V2C, SNMP_V3 -> 5000;
            case MQTT, MQTT_SSL -> 10000;
            case IEC104 -> 15000;
            default -> 5000;
        };
    }
}
