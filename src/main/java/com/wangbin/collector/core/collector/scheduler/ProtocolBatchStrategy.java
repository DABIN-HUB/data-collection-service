package com.wangbin.collector.core.collector.scheduler;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * Centralized protocol-specific batch limits.
 */
@Component
class ProtocolBatchStrategy {

    private static final BatchLimits DEFAULT_LIMITS = new BatchLimits(50, 200, 100, 50);

    private static final Map<String, BatchLimits> LIMITS = Map.ofEntries(
            Map.entry("MODBUS_TCP", new BatchLimits(125, 125, 125, 50)),
            Map.entry("MODBUS_RTU", new BatchLimits(125, 125, 125, 50)),
            Map.entry("MODBUS_ASCII", new BatchLimits(125, 125, 125, 50)),
            Map.entry("OPC_UA", new BatchLimits(100, 200, 100, 50)),
            Map.entry("OPCUA", new BatchLimits(100, 200, 100, 50)),
            Map.entry("OPC_DA", new BatchLimits(50, 100, 100, 50)),
            Map.entry("IEC104", new BatchLimits(50, 100, 100, 50)),
            Map.entry("IEC_104", new BatchLimits(50, 100, 100, 50)),
            Map.entry("IEC61850", new BatchLimits(50, 100, 100, 50)),
            Map.entry("IEC_61850", new BatchLimits(50, 100, 100, 50)),
            Map.entry("MQTT", new BatchLimits(30, 50, 50, 50)),
            Map.entry("MQTT_SSL", new BatchLimits(30, 50, 50, 50)),
            Map.entry("SNMP", new BatchLimits(20, 30, 30, 50)),
            Map.entry("SNMP_V1", new BatchLimits(20, 30, 30, 50)),
            Map.entry("SNMP_V2C", new BatchLimits(20, 30, 30, 50)),
            Map.entry("SNMP_V3", new BatchLimits(20, 30, 30, 50)),
            Map.entry("COAP", new BatchLimits(20, 20, 20, 50)),
            Map.entry("COAP_SSL", new BatchLimits(20, 20, 20, 50)),
            Map.entry("HTTP", new BatchLimits(50, 100, 100, 50)),
            Map.entry("HTTPS", new BatchLimits(50, 100, 100, 50)),
            Map.entry("WEBSOCKET", new BatchLimits(30, 50, 50, 50)),
            Map.entry("WEBSOCKET_SSL", new BatchLimits(30, 50, 50, 50)),
            Map.entry("SIEMENS_S7", new BatchLimits(200, 300, 100, 50)),
            Map.entry("ETHERNET_IP", new BatchLimits(64, 128, 128, 50)),
            Map.entry("EIP", new BatchLimits(64, 128, 128, 50)),
            Map.entry("LOGIX", new BatchLimits(64, 128, 128, 50)),
            Map.entry("ADS", new BatchLimits(64, 128, 128, 50)),
            Map.entry("AMS", new BatchLimits(64, 128, 128, 50)),
            Map.entry("OPC_UA_PLC4X", new BatchLimits(100, 200, 100, 50)),
            Map.entry("OPCUA_PLC4X", new BatchLimits(100, 200, 100, 50))
    );

    int defaultBatchSize(String protocol) {
        return resolve(protocol).defaultBatchSize();
    }

    int maxBatchSize(String protocol) {
        return resolve(protocol).maxBatchSize();
    }

    int maxMergedBatchSize(String protocol) {
        return resolve(protocol).maxMergedBatchSize();
    }

    int addressGapThreshold(String protocol) {
        return resolve(protocol).addressGapThreshold();
    }

    private BatchLimits resolve(String protocol) {
        if (protocol == null || protocol.isBlank()) {
            return DEFAULT_LIMITS;
        }
        return LIMITS.getOrDefault(protocol.toUpperCase(Locale.ROOT), DEFAULT_LIMITS);
    }

    private record BatchLimits(int defaultBatchSize,
                               int maxBatchSize,
                               int maxMergedBatchSize,
                               int addressGapThreshold) {
    }
}
