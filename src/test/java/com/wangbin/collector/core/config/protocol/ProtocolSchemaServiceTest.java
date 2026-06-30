package com.wangbin.collector.core.config.protocol;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ProtocolSchemaServiceTest {

    private final ProtocolDescriptorRegistry registry = new ProtocolDescriptorRegistry();
    private final ProtocolSchemaService service = new ProtocolSchemaService(registry);

    @Test
    void protocolSchemaServiceShouldStayConsistentWithRegistry() {
        assertEquals(registry.primaryDescriptors().size(), service.getAllSchemas().size());

        for (ProtocolDescriptor descriptor : registry.primaryDescriptors()) {
            ProtocolSchema schema = service.getSchema(descriptor.code()).orElseThrow();
            assertEquals(descriptor.code(), schema.getProtocol());
            assertEquals(descriptor.title(), schema.getTitle());
            assertEquals(descriptor.description(), schema.getDescription());
            assertEquals(descriptor.aliases(), schema.getAliases());
            assertEquals(descriptor.connectionFields(), schema.getConnectionFields());
            assertEquals(descriptor.pointAddressHints(), schema.getPointAddressHints());
            assertEquals(descriptor.implemented(), schema.isImplemented());
            assertEquals(descriptor.writable(), schema.isWritable());
            assertEquals(descriptor.subscribable(), schema.isSubscribable());
            assertEquals(expectedDataTypes(descriptor.code()), schema.getDataTypes());
            assertEquals(expectedTypeMode(descriptor.code()), schema.getTypeMode());
            assertEquals(expectedPrimaryTypeField(descriptor.code()), schema.getPrimaryTypeField());
            assertEquals(expectedPlatformDataTypeMode(descriptor.code()), schema.getPlatformDataTypeMode());
            assertNotNull(schema.getPointFields());
            assertNotNull(schema.getDriverDataTypes());
        }
    }

    @Test
    void protocolDescriptorRegistryShouldExposeAllPrimaryAndAliasSchemas() {
        Set<String> primaryCodes = registry.primaryDescriptors().stream()
                .map(ProtocolDescriptor::code)
                .collect(Collectors.toSet());

        assertEquals(primaryCodes, service.getAllSchemas().stream()
                .map(ProtocolSchema::getProtocol)
                .collect(Collectors.toSet()));

        for (String code : registry.allSupportedCodes()) {
            String canonical = registry.canonicalProtocol(code);
            assertEquals(canonical, service.getSchema(code).orElseThrow().getProtocol());
        }
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
        assertEquals(ProtocolTypeMode.PLATFORM_ONLY, customTcp.getTypeMode());
        assertEquals("dataType", customTcp.getPrimaryTypeField());
        assertEquals(PlatformDataTypeMode.REQUIRED, customTcp.getPlatformDataTypeMode());
    }

    @Test
    void shouldExposeRepresentativeCapabilitiesAndFields() {
        ProtocolSchema s7 = service.getSchema("SIEMENS_S7").orElseThrow();
        assertTrue(s7.isImplemented());
        assertTrue(s7.isWritable());
        assertTrue(s7.isSubscribable());
        assertTrue(s7.getPointAddressHints().contains("%DB1.DBX0.0:BOOL"));
        assertFalse(s7.getPointAddressHints().contains("MODE"));
        assertFalse(s7.getPointAddressHints().contains("ALM"));
        assertEquals(ProtocolTypeMode.DRIVER_PRIMARY, s7.getTypeMode());
        assertEquals("additionalConfig.driverDataType", s7.getPrimaryTypeField());
        assertEquals(PlatformDataTypeMode.DERIVED_EDITABLE, s7.getPlatformDataTypeMode());
        assertTrue(s7.isDriverTypeEnabled());
        assertEquals("S7 driver type", s7.getDriverTypeLabel());
        assertEquals("additionalConfig.driverDataType", s7.getDriverTypeField());
        assertTrue(s7.getDriverDataTypes().contains("BOOL"));
        assertTrue(s7.getConnectionFields().stream().anyMatch(field -> "rack".equals(field.getName())));
        ProtocolFieldConfig s7ControllerType = s7.getConnectionFields().stream()
                .filter(field -> "controllerType".equals(field.getName()))
                .findFirst()
                .orElseThrow();
        assertNotNull(s7ControllerType.getDescription());
        assertTrue(s7ControllerType.getDescription().contains("absolute addresses only"));
        assertTrue(s7.getPointFields().stream().anyMatch(field -> "additionalConfig.stringLength".equals(field.getName())));
        assertTrue(s7.getPointFields().stream().anyMatch(field -> "additionalConfig.arraySize".equals(field.getName())));

        ProtocolSchema mc = service.getSchema("MITSUBISHI_MC").orElseThrow();
        assertTrue(mc.isImplemented());
        assertTrue(mc.isWritable());
        assertFalse(mc.isSubscribable());
        assertEquals(ProtocolTypeMode.DRIVER_PRIMARY, mc.getTypeMode());
        assertEquals("additionalConfig.driverDataType", mc.getPrimaryTypeField());
        assertEquals(PlatformDataTypeMode.DERIVED_EDITABLE, mc.getPlatformDataTypeMode());
        assertTrue(mc.isDriverTypeEnabled());
        assertEquals("MC driver type", mc.getDriverTypeLabel());
        assertEquals("additionalConfig.driverDataType", mc.getDriverTypeField());
        assertTrue(mc.getDriverDataTypes().contains("FLOAT32"));
        assertTrue(mc.getPointAddressHints().contains("D100[4]"));
        assertTrue(mc.getConnectionFields().stream().anyMatch(field -> "networkNo".equals(field.getName())));
        assertTrue(mc.getPointFields().stream().anyMatch(field -> "additionalConfig.stringLength".equals(field.getName())));
        assertTrue(mc.getPointFields().stream().anyMatch(field -> "additionalConfig.arraySize".equals(field.getName())));

        ProtocolSchema bacnet = service.getSchema("BACNET_IP").orElseThrow();
        assertFalse(bacnet.isImplemented());
        assertTrue(bacnet.isWritable());
        assertTrue(bacnet.isSubscribable());
        assertEquals(ProtocolTypeMode.DRIVER_PRIMARY, bacnet.getTypeMode());
        assertEquals("additionalConfig.driverDataType", bacnet.getPrimaryTypeField());
        assertEquals(PlatformDataTypeMode.DERIVED_EDITABLE, bacnet.getPlatformDataTypeMode());
        assertTrue(bacnet.isDriverTypeEnabled());
        assertEquals("BACnet driver type", bacnet.getDriverTypeLabel());
        assertTrue(bacnet.getDriverDataTypes().contains("ENUM"));
        assertTrue(bacnet.getConnectionFields().stream().anyMatch(field -> "remoteDeviceInstance".equals(field.getName())));
        assertTrue(bacnet.getConnectionFields().stream().anyMatch(field -> "covEnabled".equals(field.getName())));
        assertTrue(bacnet.getPointFields().stream().anyMatch(field -> "additionalConfig.writePriority".equals(field.getName())));
        assertTrue(bacnet.getPointAddressHints().contains("analogInput:1.presentValue"));

        ProtocolSchema ethernetIp = service.getSchema("ETHERNET_IP").orElseThrow();
        assertTrue(ethernetIp.isImplemented());
        assertTrue(ethernetIp.isWritable());
        assertFalse(ethernetIp.isSubscribable());
        assertEquals(ProtocolTypeMode.DRIVER_PRIMARY, ethernetIp.getTypeMode());
        assertEquals("additionalConfig.driverDataType", ethernetIp.getPrimaryTypeField());
        assertTrue(ethernetIp.isDriverTypeEnabled());
        assertTrue(ethernetIp.getDriverDataTypes().contains("REAL"));

        ProtocolSchema knx = service.getSchema("KNXNET_IP").orElseThrow();
        assertTrue(knx.isImplemented());
        assertTrue(knx.isWritable());
        assertTrue(knx.isSubscribable());
        assertEquals(ProtocolTypeMode.PROTOCOL_FIELD_PRIMARY, knx.getTypeMode());
        assertEquals("additionalConfig.dptId", knx.getPrimaryTypeField());
        assertEquals(PlatformDataTypeMode.DERIVED_EDITABLE, knx.getPlatformDataTypeMode());
        assertTrue(knx.getConnectionFields().stream().anyMatch(field -> "groupAddressNumLevels".equals(field.getName())));
        assertTrue(knx.getConnectionFields().stream().anyMatch(field -> "knxConnectionType".equals(field.getName())));
        assertTrue(knx.getPointFields().stream().anyMatch(field -> "additionalConfig.dptId".equals(field.getName())));

        ProtocolSchema modbus = service.getSchema("MODBUS_TCP").orElseThrow();
        assertEquals(ProtocolTypeMode.PLATFORM_ONLY, modbus.getTypeMode());
        assertEquals("dataType", modbus.getPrimaryTypeField());
        assertEquals(PlatformDataTypeMode.REQUIRED, modbus.getPlatformDataTypeMode());
        assertFalse(modbus.isDriverTypeEnabled());
        assertEquals(ProtocolDescriptorRegistry.MODBUS_DATA_TYPES, modbus.getDataTypes());
        assertTrue(modbus.getPointFields().stream().anyMatch(field -> "additionalConfig.registerType".equals(field.getName())));

        ProtocolSchema opcUa = service.getSchema("OPC_UA").orElseThrow();
        assertEquals(ProtocolTypeMode.DRIVER_PRIMARY, opcUa.getTypeMode());
        assertEquals("additionalConfig.driverDataType", opcUa.getPrimaryTypeField());
        assertEquals(PlatformDataTypeMode.DERIVED_EDITABLE, opcUa.getPlatformDataTypeMode());
        assertTrue(opcUa.isDriverTypeEnabled());
        assertEquals("additionalConfig.driverDataType", opcUa.getDriverTypeField());
        assertTrue(opcUa.getConnectionFields().stream().anyMatch(field -> "authType".equals(field.getName())));
        assertTrue(opcUa.getConnectionFields().stream().anyMatch(field -> "requestTimeoutMs".equals(field.getName())));
        assertTrue(opcUa.getPointFields().stream().anyMatch(field -> "additionalConfig.nodeId".equals(field.getName())));
    }

    @Test
    void shouldExposeFieldStorageMetadata() {
        ProtocolSchema modbus = service.getSchema("MODBUS_TCP").orElseThrow();
        ProtocolFieldConfig host = modbus.getConnectionFields().stream()
                .filter(field -> "host".equals(field.getName()))
                .findFirst()
                .orElseThrow();
        ProtocolFieldConfig connectionString = modbus.getConnectionFields().stream()
                .filter(field -> "plc4xConnectionString".equals(field.getName()))
                .findFirst()
                .orElseThrow();

        assertEquals("topLevel", host.getStorage());
        assertEquals("extJson", connectionString.getStorage());
    }

    private List<String> expectedDataTypes(String protocol) {
        return switch (protocol) {
            case "MODBUS_TCP", "MODBUS_RTU" -> ProtocolDescriptorRegistry.MODBUS_DATA_TYPES;
            default -> ProtocolDescriptorRegistry.EXTENDED_DATA_TYPES;
        };
    }

    private ProtocolTypeMode expectedTypeMode(String protocol) {
        return switch (protocol) {
            case "SIEMENS_S7", "MITSUBISHI_MC", "BACNET_IP", "ETHERNET_IP", "ADS", "OPC_UA", "OPC_UA_PLC4X", "SNMP" -> ProtocolTypeMode.DRIVER_PRIMARY;
            case "KNXNET_IP" -> ProtocolTypeMode.PROTOCOL_FIELD_PRIMARY;
            default -> ProtocolTypeMode.PLATFORM_ONLY;
        };
    }

    private String expectedPrimaryTypeField(String protocol) {
        return switch (expectedTypeMode(protocol)) {
            case DRIVER_PRIMARY -> "additionalConfig.driverDataType";
            case PROTOCOL_FIELD_PRIMARY -> "additionalConfig.dptId";
            case PLATFORM_ONLY -> "dataType";
        };
    }

    private PlatformDataTypeMode expectedPlatformDataTypeMode(String protocol) {
        return switch (expectedTypeMode(protocol)) {
            case DRIVER_PRIMARY, PROTOCOL_FIELD_PRIMARY -> PlatformDataTypeMode.DERIVED_EDITABLE;
            case PLATFORM_ONLY -> PlatformDataTypeMode.REQUIRED;
        };
    }
}

