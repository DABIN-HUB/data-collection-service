package com.wangbin.collector.core.config.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolSchemaServiceTest {

    private final ProtocolSchemaService service = new ProtocolSchemaService();

    @Test
    void shouldExposePrimaryProtocolSchemas() {
        assertEquals(12, service.getAllSchemas().size());
        assertTrue(service.getSchema("MODBUS_TCP").isPresent());
        assertTrue(service.getSchema("OPC_UA").isPresent());
        assertTrue(service.getSchema("CUSTOM_TCP").isPresent());
    }

    @Test
    void shouldResolveProtocolAliases() {
        assertEquals("MQTT", service.getSchema("MQTT_SSL").orElseThrow().getProtocol());
        assertEquals("WEBSOCKET", service.getSchema("websocket-ssl").orElseThrow().getProtocol());
        assertEquals("SNMP", service.getSchema("SNMP_V3").orElseThrow().getProtocol());
        assertEquals("OPC_UA", service.getSchema("OPCUA").orElseThrow().getProtocol());
    }

    @Test
    void shouldExposeConditionalRequiredFields() {
        ProtocolFieldConfig securityName = service.getConnectionFields("SNMP_V3").stream()
                .filter(field -> "snmpSecurityName".equals(field.getName()))
                .findFirst()
                .orElseThrow();

        assertFalse(securityName.isRequired());
        assertEquals("snmpVersion=3", securityName.getRequiredWhen());
    }

    @Test
    void shouldMarkCustomTcpAsPlaceholder() {
        ProtocolSchema customTcp = service.getSchema("CUSTOM_TCP").orElseThrow();

        assertFalse(customTcp.isImplemented());
        assertTrue(customTcp.getConnectionFields().isEmpty());
    }
}
