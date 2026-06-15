package com.wangbin.collector.core.config.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolSchemaServiceTest {

    private final ProtocolSchemaService service = new ProtocolSchemaService();

    @Test
    void shouldExposePrimaryProtocolSchemas() {
        assertEquals(16, service.getAllSchemas().size());
        assertTrue(service.getSchema("MODBUS_TCP").isPresent());
        assertTrue(service.getSchema("SIEMENS_S7").isPresent());
        assertTrue(service.getSchema("ETHERNET_IP").isPresent());
        assertTrue(service.getSchema("ADS").isPresent());
        assertTrue(service.getSchema("OPC_UA").isPresent());
        assertTrue(service.getSchema("OPC_UA_PLC4X").isPresent());
        assertTrue(service.getSchema("CUSTOM_TCP").isPresent());
    }

    @Test
    void shouldResolveProtocolAliases() {
        assertEquals("MQTT", service.getSchema("MQTT_SSL").orElseThrow().getProtocol());
        assertEquals("WEBSOCKET", service.getSchema("websocket-ssl").orElseThrow().getProtocol());
        assertEquals("SNMP", service.getSchema("SNMP_V3").orElseThrow().getProtocol());
        assertEquals("OPC_UA", service.getSchema("OPCUA").orElseThrow().getProtocol());
        assertEquals("OPC_UA_PLC4X", service.getSchema("OPCUA_PLC4X").orElseThrow().getProtocol());
        assertEquals("SIEMENS_S7", service.getSchema("S7").orElseThrow().getProtocol());
        assertEquals("ETHERNET_IP", service.getSchema("LOGIX").orElseThrow().getProtocol());
        assertEquals("ADS", service.getSchema("AMS").orElseThrow().getProtocol());
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

    @Test
    void shouldExposePlc4xOverridesForModbusAndS7() {
        assertTrue(service.getConnectionFields("MODBUS_TCP").stream()
                .anyMatch(field -> "plc4xConnectionString".equals(field.getName())));
        assertTrue(service.getConnectionFields("MODBUS_ASCII").stream()
                .anyMatch(field -> "plc4xProtocolCode".equals(field.getName())));

        ProtocolSchema s7 = service.getSchema("SIEMENS_S7").orElseThrow();
        assertTrue(s7.isImplemented());
        assertTrue(s7.isWritable());
        assertTrue(s7.isSubscribable());
        assertTrue(s7.getConnectionFields().stream().anyMatch(field -> "rack".equals(field.getName())));
        assertTrue(s7.getConnectionFields().stream().anyMatch(field -> "maxFieldsPerRequest".equals(field.getName())));

        ProtocolSchema ethernetIp = service.getSchema("ETHERNET_IP").orElseThrow();
        assertTrue(ethernetIp.isImplemented());
        assertTrue(ethernetIp.isWritable());
        assertFalse(ethernetIp.isSubscribable());
        assertTrue(ethernetIp.getConnectionFields().stream().anyMatch(field -> "communicationPath".equals(field.getName())));
        assertTrue(ethernetIp.getConnectionFields().stream().anyMatch(field -> "plc4xConnectionString".equals(field.getName())));

        ProtocolSchema ads = service.getSchema("ADS").orElseThrow();
        assertTrue(ads.isImplemented());
        assertTrue(ads.isWritable());
        assertTrue(ads.isSubscribable());
        assertTrue(ads.getConnectionFields().stream().anyMatch(field -> "targetAmsNetId".equals(field.getName())));
        assertTrue(ads.getConnectionFields().stream().anyMatch(field -> "sourceAmsPort".equals(field.getName())));

        ProtocolSchema opcUa = service.getSchema("OPC_UA").orElseThrow();
        assertTrue(opcUa.isImplemented());
        assertTrue(opcUa.isWritable());
        assertTrue(opcUa.isSubscribable());
        assertTrue(opcUa.getConnectionFields().stream().anyMatch(field -> "authType".equals(field.getName())));
        assertTrue(opcUa.getConnectionFields().stream().anyMatch(field -> "messageSecurity".equals(field.getName())));
        assertTrue(opcUa.getConnectionFields().stream().anyMatch(field -> "keyStoreFile".equals(field.getName())));
        assertTrue(opcUa.getConnectionFields().stream().anyMatch(field -> "requestTimeoutMs".equals(field.getName())));

        ProtocolSchema plc4xOpcUa = service.getSchema("OPC_UA_PLC4X").orElseThrow();
        assertTrue(plc4xOpcUa.isImplemented());
        assertTrue(plc4xOpcUa.isWritable());
        assertTrue(plc4xOpcUa.isSubscribable());
        assertTrue(plc4xOpcUa.getConnectionFields().stream().anyMatch(field -> "authType".equals(field.getName())));
        assertTrue(plc4xOpcUa.getConnectionFields().stream().anyMatch(field -> "messageSecurity".equals(field.getName())));
        assertTrue(plc4xOpcUa.getConnectionFields().stream().anyMatch(field -> "clientCertPath".equals(field.getName())));
        assertTrue(plc4xOpcUa.getConnectionFields().stream().anyMatch(field -> "keyStoreFile".equals(field.getName())));
        assertTrue(plc4xOpcUa.getConnectionFields().stream().anyMatch(field -> "requestTimeoutMs".equals(field.getName())));
    }
}
