package com.wangbin.collector.core.collector.scheduler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProtocolBatchStrategyTest {

    private final ProtocolBatchStrategy strategy = new ProtocolBatchStrategy();

    @Test
    void shouldReturnProtocolDefaultsAndMaxLimits() {
        assertEquals(125, strategy.defaultBatchSize("MODBUS_TCP"));
        assertEquals(125, strategy.maxBatchSize("MODBUS_RTU"));
        assertEquals(200, strategy.defaultBatchSize("SIEMENS_S7"));
        assertEquals(64, strategy.defaultBatchSize("ETHERNET_IP"));
        assertEquals(64, strategy.defaultBatchSize("ADS"));
        assertEquals(100, strategy.defaultBatchSize("OPC_UA"));
        assertEquals(100, strategy.defaultBatchSize("OPC_UA_PLC4X"));
        assertEquals(200, strategy.maxBatchSize("OPCUA"));
        assertEquals(200, strategy.maxBatchSize("OPCUA_PLC4X"));
        assertEquals(30, strategy.defaultBatchSize("MQTT_SSL"));
        assertEquals(30, strategy.maxBatchSize("SNMP_V3"));
        assertEquals(20, strategy.maxBatchSize("COAP_SSL"));
    }

    @Test
    void shouldFallbackForUnknownProtocol() {
        assertEquals(50, strategy.defaultBatchSize("UNKNOWN"));
        assertEquals(200, strategy.maxBatchSize(null));
        assertEquals(100, strategy.maxMergedBatchSize(""));
    }
}
