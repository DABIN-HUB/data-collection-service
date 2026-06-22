package com.wangbin.collector.core.config.protocol;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
            assertEquals(ProtocolDescriptorRegistry.COMMON_DATA_TYPES, schema.getDataTypes());
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
    }

    @Test
    void shouldExposeRepresentativeCapabilitiesAndFields() {
        ProtocolSchema s7 = service.getSchema("SIEMENS_S7").orElseThrow();
        assertTrue(s7.isImplemented());
        assertTrue(s7.isWritable());
        assertTrue(s7.isSubscribable());
        assertTrue(s7.getConnectionFields().stream().anyMatch(field -> "rack".equals(field.getName())));

        ProtocolSchema ethernetIp = service.getSchema("ETHERNET_IP").orElseThrow();
        assertTrue(ethernetIp.isImplemented());
        assertTrue(ethernetIp.isWritable());
        assertFalse(ethernetIp.isSubscribable());

        ProtocolSchema knx = service.getSchema("KNXNET_IP").orElseThrow();
        assertTrue(knx.isImplemented());
        assertTrue(knx.isWritable());
        assertTrue(knx.isSubscribable());
        assertTrue(knx.getConnectionFields().stream().anyMatch(field -> "groupAddressNumLevels".equals(field.getName())));
        assertTrue(knx.getConnectionFields().stream().anyMatch(field -> "knxConnectionType".equals(field.getName())));

        ProtocolSchema opcUa = service.getSchema("OPC_UA").orElseThrow();
        assertTrue(opcUa.getConnectionFields().stream().anyMatch(field -> "authType".equals(field.getName())));
        assertTrue(opcUa.getConnectionFields().stream().anyMatch(field -> "requestTimeoutMs".equals(field.getName())));
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
}
