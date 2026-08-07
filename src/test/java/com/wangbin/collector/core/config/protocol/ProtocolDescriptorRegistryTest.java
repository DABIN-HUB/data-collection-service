package com.wangbin.collector.core.config.protocol;

import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.collector.protocol.base.ProtocolCollector;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProtocolDescriptorRegistryTest {

    private static final List<String> EXPECTED_PRIMARY_CODE_ORDER = List.of(
            "MODBUS_TCP", "MODBUS_RTU", "SIEMENS_S7", "OPC_UA", "OPC_UA_PLC4X", "OPC_UA_MILO",
            "BACNET_IP", "BACNET_MSTP", "BACNET_SC", "MITSUBISHI_MC", "OMRON_FINS",
            "ETHERNET_IP", "ADS", "KNXNET_IP", "OPC_DA", "SNMP", "COAP", "MQTT",
            "IEC104", "DLT645_2007", "IEC101", "IEC61850", "HTTP", "WEBSOCKET",
            "CUSTOM_TCP", "CUSTOM_UDP");
    private static final Set<String> EXPECTED_PRIMARY_CODES = Set.copyOf(EXPECTED_PRIMARY_CODE_ORDER);

    private static final Set<String> EXPECTED_ALIASES = Set.of(
            "MODBUS_ASCII", "S7", "OPCUA", "OPCUA_PLC4X", "OPCUA_MILO",
            "BACNET", "BACNETIP", "BACNET/IP", "BACNETMSTP", "BACNET_MS/TP", "BACNETSC", "BACNET/SC",
            "MC", "MELSEC_MC", "FINS", "OMRONFINS", "EIP", "LOGIX", "AB_ETH", "AMS",
            "KNX", "KNXNETIP", "KNXNET/IP", "KNX_NET_IP", "SNMP_V1", "SNMP_V2C", "SNMP_V3",
            "COAP_SSL", "MQTT_SSL", "IEC_104", "IEC_101", "IEC60870_5_101", "IEC_61850",
            "DLT645", "DL_T_645", "DLT_645_2007", "HTTPS", "WEBSOCKET_SSL");

    @Test
    void allProvidersShouldBeDiscoveredAndRegistered() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.scan("com.wangbin.collector.core.config.protocol");
            context.refresh();

            assertEquals(20, context.getBeanNamesForType(ProtocolDescriptorProvider.class).length);
            ProtocolDescriptorRegistry registry = context.getBean(ProtocolDescriptorRegistry.class);
            assertEquals(EXPECTED_PRIMARY_CODES, primaryCodes(registry));
            assertEquals(EXPECTED_PRIMARY_CODE_ORDER, primaryCodeOrder(registry));
        }
    }

    @Test
    void descriptorMetadataMustRemainCompatible() {
        ProtocolDescriptorRegistry registry = ProtocolDescriptorTestProviders.registry();

        assertEquals(26, registry.primaryDescriptors().size());
        assertEquals(EXPECTED_PRIMARY_CODES, primaryCodes(registry));
        assertEquals(EXPECTED_PRIMARY_CODE_ORDER, primaryCodeOrder(registry));
        assertEquals(EXPECTED_ALIASES, aliases(registry));

        Map<String, ExpectedMetadata> expected = expectedMetadata();
        for (ProtocolDescriptor descriptor : registry.primaryDescriptors()) {
            ExpectedMetadata metadata = expected.get(descriptor.code());
            assertEquals(metadata.collectorSimpleName(), descriptor.collectorClass().getSimpleName(), descriptor.code());
            assertEquals(metadata.connectionType(), descriptor.connectionType(), descriptor.code());
            assertEquals(metadata.defaultPort(), descriptor.defaultPort(), descriptor.code());
            assertEquals(metadata.addressingMode(), descriptor.addressingMode(), descriptor.code());
            assertEquals(metadata.implementationState(), descriptor.implementationState(), descriptor.code());
            assertEquals(metadata.writeCapability(), descriptor.writeCapability(), descriptor.code());
            assertEquals(metadata.subscriptionCapability(), descriptor.subscriptionCapability(), descriptor.code());
            assertEquals(metadata.browseCapability(), descriptor.browseCapability(), descriptor.code());
            assertEquals(metadata.aliases(), descriptor.aliases(), descriptor.code());
        }
    }

    @Test
    void aliasesShouldResolveToPrimaryDescriptor() {
        ProtocolDescriptorRegistry registry = ProtocolDescriptorTestProviders.registry();

        assertEquals("MODBUS_RTU", registry.resolve("modbus-ascii").code());
        assertEquals("BACNET_IP", registry.resolve("BACNET/IP").code());
        assertEquals("BACNET_MSTP", registry.resolve("BACNET-MS/TP").code());
        assertEquals("BACNET_SC", registry.resolve("BACNET-SC").code());
        assertEquals("HTTP", registry.resolve("HTTPS").code());
        assertEquals("MQTT", registry.resolve("MQTT_SSL").code());
        assertEquals("SNMP", registry.resolve("SNMP_V3").code());
        assertEquals("IEC101", registry.resolve("IEC_101").code());
        assertEquals("DLT645_2007", registry.resolve("DLT645").code());
    }

    @Test
    void duplicateProtocolCodeMustFailFast() {
        ProtocolDescriptorProvider first = registry -> registry.registerPrimary(descriptor("DUPLICATE", List.of()));
        ProtocolDescriptorProvider second = registry -> registry.registerPrimary(descriptor("DUPLICATE", List.of()));

        assertThrows(IllegalStateException.class, () -> new ProtocolDescriptorRegistry(List.of(first, second)));
    }

    @Test
    void duplicateAliasMustFailFast() {
        ProtocolDescriptorProvider first = registry -> registry.registerPrimary(descriptor("PRIMARY_A", List.of("SHARED")));
        ProtocolDescriptorProvider second = registry -> registry.registerPrimary(descriptor("PRIMARY_B", List.of("SHARED")));

        assertThrows(IllegalStateException.class, () -> new ProtocolDescriptorRegistry(List.of(first, second)));
    }

    @Test
    void aliasMustNotConflictWithPrimaryCode() {
        ProtocolDescriptorProvider first = registry -> registry.registerPrimary(descriptor("PRIMARY_A", List.of()));
        ProtocolDescriptorProvider second = registry -> registry.registerPrimary(descriptor("PRIMARY_B", List.of("PRIMARY_A")));

        assertThrows(IllegalStateException.class, () -> new ProtocolDescriptorRegistry(List.of(first, second)));
    }

    @Test
    void registryMustNotDependOnConcreteCollectorImplementations() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/wangbin/collector/core/config/protocol/ProtocolDescriptorRegistry.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("core.collector.protocol.ads"));
        assertFalse(source.contains("core.collector.protocol.bacnet"));
        assertFalse(source.contains("core.collector.protocol.modbus"));
        assertFalse(source.contains("new ModbusProtocolDescriptorProvider"));
        assertFalse(source.contains("descriptor(\"MODBUS"));
        assertFalse(source.contains("descriptor(\"BACNET"));
    }

    private static Set<String> primaryCodes(ProtocolDescriptorRegistry registry) {
        return registry.primaryDescriptors().stream()
                .map(ProtocolDescriptor::code)
                .collect(Collectors.toSet());
    }

    private static List<String> primaryCodeOrder(ProtocolDescriptorRegistry registry) {
        return registry.primaryDescriptors().stream()
                .map(ProtocolDescriptor::code)
                .toList();
    }

    private static Set<String> aliases(ProtocolDescriptorRegistry registry) {
        Set<String> primaryCodes = primaryCodes(registry);
        return registry.allSupportedCodes().stream()
                .filter(code -> !primaryCodes.contains(code))
                .collect(Collectors.toSet());
    }

    private static ProtocolDescriptor descriptor(String code, List<String> aliases) {
        return new ProtocolDescriptor(code, code, code, aliases, TestCollector.class, code, null,
                ProtocolAddressingMode.NUMERIC, true, false, false, List.of(), List.of());
    }

    private Map<String, ExpectedMetadata> expectedMetadata() {
        return Map.ofEntries(
                metadata("MODBUS_TCP", "Plc4xModbusTcpCollector", "MODBUS_TCP", 502,
                        ProtocolAddressingMode.NUMERIC, supported(), supported(), unsupported(), unsupported(), List.of()),
                metadata("MODBUS_RTU", "Plc4xModbusRtuCollector", "MODBUS_RTU", null,
                        ProtocolAddressingMode.NUMERIC, supported(), supported(), unsupported(), unsupported(), List.of("MODBUS_ASCII")),
                metadata("SIEMENS_S7", "S7Collector", "SIEMENS_S7", 102,
                        ProtocolAddressingMode.MIXED, supported(), supported(), runtime(), unsupported(), List.of("S7")),
                metadata("OPC_UA", "Plc4xOpcUaCollector", "OPC_UA", 4840,
                        ProtocolAddressingMode.SYMBOLIC, supported(), supported(), runtime(), runtime(), List.of("OPCUA")),
                metadata("OPC_UA_PLC4X", "Plc4xOpcUaCollector", "OPC_UA_PLC4X", 4840,
                        ProtocolAddressingMode.SYMBOLIC, supported(), supported(), runtime(), runtime(), List.of("OPCUA_PLC4X")),
                metadata("OPC_UA_MILO", "OpcUaCollector", "OPC_UA_MILO", 4840,
                        ProtocolAddressingMode.SYMBOLIC, experimental(), supported(), runtime(), runtime(), List.of("OPCUA_MILO")),
                metadata("BACNET_IP", "BacnetIpCollector", "BACNET_IP", 47808,
                        ProtocolAddressingMode.MIXED, supported(), supported(), runtime(), unsupported(),
                        List.of("BACNET", "BACNETIP", "BACNET/IP")),
                metadata("BACNET_MSTP", "BacnetMstpCollector", "BACNET_MSTP", null,
                        ProtocolAddressingMode.MIXED, supported(), supported(), runtime(), unsupported(),
                        List.of("BACNETMSTP", "BACNET-MS/TP", "BACNET_MSTP")),
                metadata("BACNET_SC", "BacnetScCollector", "BACNET_SC", 443,
                        ProtocolAddressingMode.MIXED, experimental(), supported(), runtime(), unsupported(),
                        List.of("BACNETSC", "BACNET/SC", "BACNET-SC")),
                metadata("MITSUBISHI_MC", "McCollector", "MITSUBISHI_MC", 5000,
                        ProtocolAddressingMode.MIXED, supported(), supported(), unsupported(), unsupported(), List.of("MC", "MELSEC_MC")),
                metadata("OMRON_FINS", "OmronFinsCollector", "OMRON_FINS", 9600,
                        ProtocolAddressingMode.MIXED, supported(), supported(), unsupported(), unsupported(), List.of("FINS", "OMRONFINS")),
                metadata("ETHERNET_IP", "EtherNetIpCollector", "ETHERNET_IP", 44818,
                        ProtocolAddressingMode.SYMBOLIC, supported(), supported(), unsupported(), unsupported(), List.of("EIP", "LOGIX", "AB_ETH")),
                metadata("ADS", "AdsCollector", "ADS", 48898,
                        ProtocolAddressingMode.SYMBOLIC, supported(), supported(), runtime(), unsupported(), List.of("AMS")),
                metadata("KNXNET_IP", "KnxNetIpCollector", "KNXNET_IP", 3671,
                        ProtocolAddressingMode.SYMBOLIC, supported(), supported(), runtime(), unsupported(),
                        List.of("KNX", "KNXNETIP", "KNXNET/IP", "KNX_NET_IP")),
                metadata("OPC_DA", "OpcDaCollector", "OPC_DA", null,
                        ProtocolAddressingMode.SYMBOLIC, supported(), supported(), runtime(), runtime(), List.of()),
                metadata("SNMP", "SnmpCollector", "SNMP", 161,
                        ProtocolAddressingMode.NUMERIC, supported(), supported(), runtime(), supported(), List.of("SNMP_V1", "SNMP_V2C", "SNMP_V3")),
                metadata("COAP", "CoapCollector", "COAP", 5683,
                        ProtocolAddressingMode.SYMBOLIC, supported(), supported(), runtime(), unsupported(), List.of("COAP_SSL")),
                metadata("MQTT", "MqttCollector", "MQTT", 1883,
                        ProtocolAddressingMode.SYMBOLIC, supported(), supported(), supported(), unsupported(), List.of("MQTT_SSL")),
                metadata("IEC104", "Iec104Collector", "IEC104", 2404,
                        ProtocolAddressingMode.NUMERIC, supported(), supported(), runtime(), unsupported(), List.of("IEC_104")),
                metadata("DLT645_2007", "Dlt645Collector", "DLT645_2007", null,
                        ProtocolAddressingMode.SYMBOLIC, experimental(), experimental(), unsupported(), unsupported(),
                        List.of("DLT645", "DL_T_645", "DLT_645_2007")),
                metadata("IEC101", "Iec101Collector", "IEC101", null,
                        ProtocolAddressingMode.MIXED, experimental(), experimental(), experimental(), unsupported(), List.of("IEC_101", "IEC60870_5_101")),
                metadata("IEC61850", "Iec61850Collector", "IEC61850", 102,
                        ProtocolAddressingMode.SYMBOLIC, supported(), supported(), runtime(), runtime(), List.of("IEC_61850")),
                metadata("HTTP", "HttpCollector", "HTTP", 80,
                        ProtocolAddressingMode.SYMBOLIC, supported(), supported(), unsupported(), unsupported(), List.of("HTTPS")),
                metadata("WEBSOCKET", "WebSocketCollector", "WEBSOCKET", 80,
                        ProtocolAddressingMode.SYMBOLIC, supported(), supported(), supported(), unsupported(), List.of("WEBSOCKET_SSL")),
                metadata("CUSTOM_TCP", "CustomProtocolCollector", "CUSTOM_TCP", null,
                        ProtocolAddressingMode.MIXED, experimental(), supported(), unsupported(), unsupported(), List.of()),
                metadata("CUSTOM_UDP", "CustomProtocolCollector", "CUSTOM_UDP", null,
                        ProtocolAddressingMode.MIXED, experimental(), supported(), unsupported(), unsupported(), List.of())
        );
    }

    private static Map.Entry<String, ExpectedMetadata> metadata(String code,
                                                               String collectorSimpleName,
                                                               String connectionType,
                                                               Integer defaultPort,
                                                               ProtocolAddressingMode addressingMode,
                                                               ProtocolCapabilityState implementationState,
                                                               ProtocolCapabilityState writeCapability,
                                                               ProtocolCapabilityState subscriptionCapability,
                                                               ProtocolCapabilityState browseCapability,
                                                               List<String> aliases) {
        return Map.entry(code, new ExpectedMetadata(collectorSimpleName, connectionType, defaultPort, addressingMode,
                implementationState, writeCapability, subscriptionCapability, browseCapability, aliases));
    }

    private static ProtocolCapabilityState supported() {
        return ProtocolCapabilityState.SUPPORTED;
    }

    private static ProtocolCapabilityState unsupported() {
        return ProtocolCapabilityState.UNSUPPORTED;
    }

    private static ProtocolCapabilityState runtime() {
        return ProtocolCapabilityState.RUNTIME_DEPENDENT;
    }

    private static ProtocolCapabilityState experimental() {
        return ProtocolCapabilityState.EXPERIMENTAL;
    }

    private record ExpectedMetadata(String collectorSimpleName,
                                    String connectionType,
                                    Integer defaultPort,
                                    ProtocolAddressingMode addressingMode,
                                    ProtocolCapabilityState implementationState,
                                    ProtocolCapabilityState writeCapability,
                                    ProtocolCapabilityState subscriptionCapability,
                                    ProtocolCapabilityState browseCapability,
                                    List<String> aliases) {
    }

    private static final class TestCollector implements ProtocolCollector {

        @Override
        public void init(DeviceInfo deviceInfo) throws CollectorException {
        }

        @Override
        public void connect() throws CollectorException {
        }

        @Override
        public void disconnect() throws CollectorException {
        }

        @Override
        public boolean isConnected() {
            return false;
        }

        @Override
        public String getConnectionStatus() {
            return "DISCONNECTED";
        }

        @Override
        public String getLastError() {
            return null;
        }

        @Override
        public Map<String, Object> getStatistics() {
            return Map.of();
        }

        @Override
        public void resetStatistics() {
        }

        @Override
        public void destroy() {
        }

        @Override
        public Map<String, Object> getDeviceStatus() throws CollectorException {
            return Map.of();
        }

        @Override
        public String getCollectorType() {
            return "TEST";
        }

        @Override
        public String getProtocolType() {
            return "TEST";
        }
    }
}
