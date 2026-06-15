package com.wangbin.collector.common.domain.enums;

/**
 * Supported collection protocol types.
 */
public enum ProtocolType {

    // Modbus
    MODBUS_TCP("MODBUS_TCP", "Modbus TCP", 502),
    MODBUS_RTU("MODBUS_RTU", "Modbus RTU", null),
    MODBUS_ASCII("MODBUS_ASCII", "Modbus ASCII", null),
    SIEMENS_S7("SIEMENS_S7", "Siemens S7", 102),
    ETHERNET_IP("ETHERNET_IP", "EtherNet/IP", 44818),
    ADS("ADS", "Beckhoff ADS", 48898),

    // OPC
    OPC_DA("OPC_DA", "OPC DA", null),
    OPC_UA("OPC_UA", "OPC UA", 4840),
    OPC_UA_PLC4X("OPC_UA_PLC4X", "OPC UA (PLC4X Validation)", 4840),

    // SNMP
    SNMP_V1("SNMP_V1", "SNMP v1", 161),
    SNMP_V2C("SNMP_V2C", "SNMP v2c", 161),
    SNMP_V3("SNMP_V3", "SNMP v3", 161),

    // MQTT
    MQTT("MQTT", "MQTT", 1883),
    MQTT_SSL("MQTT_SSL", "MQTT SSL", 8883),

    // CoAP
    COAP("COAP", "CoAP", 5683),
    COAP_SSL("COAP_SSL", "CoAP SSL", 5684),

    // IEC
    IEC104("IEC104", "IEC 60870-5-104", 2404),
    IEC61850("IEC61850", "IEC 61850", 102),

    // HTTP
    HTTP("HTTP", "HTTP", 80),
    HTTPS("HTTPS", "HTTPS", 443),

    // WebSocket
    WEBSOCKET("WEBSOCKET", "WebSocket", 80),
    WEBSOCKET_SSL("WEBSOCKET_SSL", "WebSocket SSL", 443),

    // Custom
    CUSTOM_TCP("CUSTOM_TCP", "Custom TCP", null),
    CUSTOM_UDP("CUSTOM_UDP", "Custom UDP", null);

    private final String code;
    private final String description;
    private final Integer defaultPort;

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
                || this == CUSTOM_TCP;
    }

    public boolean isSerialProtocol() {
        return this == MODBUS_RTU || this == MODBUS_ASCII;
    }

    public boolean needEncryption() {
        return this == HTTPS
                || this == MQTT_SSL
                || this == COAP_SSL
                || this == WEBSOCKET_SSL;
    }

    public int getDefaultTimeout() {
        return switch (this) {
            case MODBUS_TCP, MODBUS_RTU, MODBUS_ASCII -> 3000;
            case SIEMENS_S7, ETHERNET_IP, ADS -> 5000;
            case OPC_UA, OPC_UA_PLC4X -> 10000;
            case SNMP_V1, SNMP_V2C, SNMP_V3 -> 5000;
            case MQTT, MQTT_SSL -> 10000;
            case IEC104 -> 15000;
            default -> 5000;
        };
    }
}
