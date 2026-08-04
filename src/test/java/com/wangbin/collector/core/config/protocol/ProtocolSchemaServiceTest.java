package com.wangbin.collector.core.config.protocol;

import org.junit.jupiter.api.Test;

import java.util.Collection;
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
            assertTrue(schema.getConnectionFields().containsAll(descriptor.connectionFields()));
            int fallbackFieldCount = descriptor.subscribable()
                    && descriptor.connectionFields().stream()
                    .noneMatch(field -> "subscriptionFallbackStrategy".equals(field.getName())) ? 1 : 0;
            assertEquals(descriptor.connectionFields().size() + fallbackFieldCount,
                    schema.getConnectionFields().size());
            assertEquals(descriptor.pointAddressHints(), schema.getPointAddressHints());
            assertEquals(descriptor.implemented(), schema.isImplemented());
            assertEquals(descriptor.writable(), schema.isWritable());
            assertEquals(descriptor.subscribable(), schema.isSubscribable());
            assertEquals(descriptor.implementationState(), schema.getImplementationState());
            assertEquals(descriptor.writeCapability(), schema.getWriteCapability());
            assertEquals(descriptor.subscriptionCapability(), schema.getSubscriptionCapability());
            assertEquals(descriptor.browseCapability(), schema.getBrowseCapability());
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
    void shouldExposeCustomProtocolAsExperimental() {
        ProtocolSchema customTcp = service.getSchema("CUSTOM_TCP").orElseThrow();

        assertTrue(customTcp.isImplemented());
        assertEquals(ProtocolCapabilityState.EXPERIMENTAL, customTcp.getImplementationState());
        assertTrue(customTcp.isWritable());
        assertFalse(customTcp.isSubscribable());
        assertTrue(customTcp.getConnectionFields().stream()
                .anyMatch(field -> "frameMode".equals(field.getName())));
        assertTrue(customTcp.getPointFields().stream()
                .anyMatch(field -> "additionalConfig.requestTemplate".equals(field.getName())));
        assertEquals(ProtocolTypeMode.PLATFORM_ONLY, customTcp.getTypeMode());
        assertEquals("dataType", customTcp.getPrimaryTypeField());
        assertEquals(PlatformDataTypeMode.REQUIRED, customTcp.getPlatformDataTypeMode());

        ProtocolSchema customUdp = service.getSchema("CUSTOM_UDP").orElseThrow();
        assertEquals(ProtocolCapabilityState.EXPERIMENTAL, customUdp.getImplementationState());
        assertFalse(customUdp.getConnectionFields().stream()
                .anyMatch(field -> "frameMode".equals(field.getName())));
    }

    @Test
    void shouldExposeAccurateRuntimeCapabilities() {
        ProtocolSchema bacnetSc = service.getSchema("BACNET_SC").orElseThrow();
        assertEquals(ProtocolCapabilityState.EXPERIMENTAL, bacnetSc.getImplementationState());
        assertEquals(ProtocolCapabilityState.RUNTIME_DEPENDENT, bacnetSc.getSubscriptionCapability());

        ProtocolSchema snmp = service.getSchema("SNMP").orElseThrow();
        assertTrue(snmp.isWritable());
        assertTrue(snmp.isSubscribable());
        assertEquals(ProtocolCapabilityState.RUNTIME_DEPENDENT, snmp.getSubscriptionCapability());
        assertEquals(ProtocolCapabilityState.SUPPORTED, snmp.getBrowseCapability());

        ProtocolSchema coap = service.getSchema("COAP").orElseThrow();
        assertTrue(coap.isSubscribable());
        assertEquals(ProtocolCapabilityState.RUNTIME_DEPENDENT, coap.getSubscriptionCapability());

        ProtocolSchema iec104 = service.getSchema("IEC104").orElseThrow();
        assertTrue(iec104.isWritable());
        assertTrue(iec104.isSubscribable());

        ProtocolSchema iec61850 = service.getSchema("IEC61850").orElseThrow();
        assertTrue(iec61850.isWritable());
        assertTrue(iec61850.isSubscribable());
        assertEquals(ProtocolCapabilityState.RUNTIME_DEPENDENT, iec61850.getBrowseCapability());

        ProtocolSchema dlt645 = service.getSchema("DLT645").orElseThrow();
        assertEquals("DLT645_2007", dlt645.getProtocol());
        assertEquals(ProtocolCapabilityState.EXPERIMENTAL, dlt645.getImplementationState());
        assertEquals(ProtocolCapabilityState.EXPERIMENTAL, dlt645.getWriteCapability());
        assertFalse(dlt645.isSubscribable());
        assertTrue(dlt645.getConnectionFields().stream()
                .anyMatch(field -> "meterAddress".equals(field.getName())));

        ProtocolSchema iec101 = service.getSchema("IEC_101").orElseThrow();
        assertEquals("IEC101", iec101.getProtocol());
        assertEquals(ProtocolCapabilityState.EXPERIMENTAL, iec101.getImplementationState());
        assertEquals(ProtocolCapabilityState.EXPERIMENTAL, iec101.getSubscriptionCapability());
        assertTrue(iec101.getPointFields().stream()
                .anyMatch(field -> "additionalConfig.writeSelect".equals(field.getName())));
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
        assertTrue(s7ControllerType.getDescription().contains("绝对地址"));
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

        ProtocolSchema fins = service.getSchema("OMRON_FINS").orElseThrow();
        assertTrue(fins.isImplemented());
        assertTrue(fins.isWritable());
        assertFalse(fins.isSubscribable());
        assertEquals(ProtocolTypeMode.PLATFORM_ONLY, fins.getTypeMode());
        assertEquals("dataType", fins.getPrimaryTypeField());
        assertEquals(PlatformDataTypeMode.REQUIRED, fins.getPlatformDataTypeMode());
        assertFalse(fins.isDriverTypeEnabled());
        assertTrue(fins.getPointAddressHints().contains("DM:100"));
        assertTrue(fins.getConnectionFields().stream().anyMatch(field -> "plcNode".equals(field.getName())));
        assertTrue(fins.getConnectionFields().stream().anyMatch(field -> "batchReadEnabled".equals(field.getName())));
        assertTrue(fins.getPointFields().stream().anyMatch(field -> "additionalConfig.stringLength".equals(field.getName())));
        assertTrue(fins.getPointFields().stream().anyMatch(field -> "additionalConfig.byteOrder".equals(field.getName())));

        ProtocolSchema bacnet = service.getSchema("BACNET_IP").orElseThrow();
        assertTrue(bacnet.isImplemented());
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

        ProtocolSchema opcUaMilo = service.getSchema("OPC_UA_MILO").orElseThrow();
        assertTrue(opcUaMilo.getConnectionFields().stream()
                .noneMatch(field -> "plc4xConnectionString".equals(field.getName())));
    }

    @Test
    void shouldKeepCriticalDynamicFormFieldsForRepresentativeProtocols() {
        assertSchemaFields("MODBUS_TCP",
                List.of("host", "port", "slaveId", "byteOrder", "maxRegistersPerRequest", "plc4xConnectionString"),
                List.of("additionalConfig.registerType", "additionalConfig.byteOrder",
                        "additionalConfig.wordOrder", "additionalConfig.stringLength"));
        assertSchemaFields("SIEMENS_S7",
                List.of("host", "port", "rack", "slot", "controllerType", "plc4xConnectionString"),
                List.of("additionalConfig.subscriptionMode", "additionalConfig.stringLength",
                        "additionalConfig.arraySize"));        assertSchemaFields("OPC_UA",
                List.of("url", "endpointUrl", "host", "port", "authType", "securityPolicy",
                        "requestTimeoutMs", "plc4xConnectionString"),
                List.of("additionalConfig.nodeId", "additionalConfig.identifierType",
                        "additionalConfig.samplingInterval", "additionalConfig.arraySize", "additionalConfig.subscribe"));
        assertSchemaFields("BACNET_IP",
                List.of("host", "port", "remoteDeviceInstance", "localDeviceInstance",
                        "covEnabled", "readPropertyMultipleEnabled"),
                List.of("additionalConfig.driverDataType", "additionalConfig.writePriority", "additionalConfig.covMode"));
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

    private void assertSchemaFields(String protocol,
                                    Collection<String> connectionFields,
                                    Collection<String> pointFields) {
        ProtocolSchema schema = service.getSchema(protocol).orElseThrow();
        assertFieldNames(protocol + " 连接字段", schema.getConnectionFields(), connectionFields);
        assertFieldNames(protocol + " 点位字段", schema.getPointFields(), pointFields);
    }

    private void assertFieldNames(String scope,
                                  List<ProtocolFieldConfig> fields,
                                  Collection<String> expectedNames) {
        Set<String> names = fields.stream()
                .map(ProtocolFieldConfig::getName)
                .collect(Collectors.toSet());
        assertTrue(names.containsAll(expectedNames), () -> scope + " 缺少字段: "
                + expectedNames.stream()
                .filter(name -> !names.contains(name))
                .toList());
    }
    private List<String> expectedDataTypes(String protocol) {
        return switch (protocol) {
            case "MODBUS_TCP", "MODBUS_RTU" -> ProtocolDescriptorRegistry.MODBUS_DATA_TYPES;
            default -> ProtocolDescriptorRegistry.EXTENDED_DATA_TYPES;
        };
    }

    private ProtocolTypeMode expectedTypeMode(String protocol) {
        return switch (protocol) {
            case "SIEMENS_S7", "MITSUBISHI_MC", "BACNET_IP", "BACNET_MSTP", "BACNET_SC", "ETHERNET_IP", "ADS", "OPC_UA", "OPC_UA_PLC4X", "OPC_UA_MILO", "SNMP" -> ProtocolTypeMode.DRIVER_PRIMARY;
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

