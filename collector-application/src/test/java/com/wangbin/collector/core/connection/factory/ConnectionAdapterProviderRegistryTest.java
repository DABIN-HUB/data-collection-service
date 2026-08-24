package com.wangbin.collector.core.connection.factory;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.config.protocol.ProtocolDescriptor;
import com.wangbin.collector.core.config.protocol.ProtocolDescriptorRegistry;
import com.wangbin.collector.core.config.protocol.ProtocolDescriptorTestProviders;
import com.wangbin.collector.core.config.validator.ProtocolConnectionValidator;
import com.wangbin.collector.core.connection.adapter.AdsConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.BacnetIpConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.BacnetMstpConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.BacnetScConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.CoapConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.ConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.CustomTcpConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.CustomUdpConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.Dlt645ConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.EtherNetIpConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.HttpConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.Iec101ConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.Iec104ConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.Iec61850ConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.KnxNetIpConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.MitsubishiMcConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.MqttConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.OmronFinsUdpConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.OpcUaConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.Plc4xModbusRtuConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.Plc4xModbusTcpConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.Plc4xOpcUaConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.S7ConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.SnmpConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.TcpConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.WebSocketConnectionAdapter;
import com.wangbin.collector.core.connection.factory.provider.BacnetConnectionAdapterProvider;
import com.wangbin.collector.core.connection.factory.provider.Dlt645ConnectionAdapterProvider;
import com.wangbin.collector.core.connection.factory.provider.Iec101ConnectionAdapterProvider;
import com.wangbin.collector.core.connection.factory.provider.MqttConnectionAdapterProvider;
import com.wangbin.collector.core.connection.factory.provider.WebSocketConnectionAdapterProvider;
import com.wangbin.collector.core.connection.serial.SharedSerialChannelManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConnectionAdapterProviderRegistryTest {

    private static final Set<String> EXPECTED_PROVIDER_TYPES = Set.of(
            "TCP", "HTTP", "MQTT", "WEBSOCKET", "COAP", "SIEMENS_S7",
            "BACNET_IP", "BACNET_MSTP", "BACNET_SC", "MITSUBISHI_MC", "OMRON_FINS",
            "ETHERNET_IP", "ADS", "KNXNET_IP", "MODBUS_TCP", "MODBUS_RTU", "SNMP",
            "OPC_UA", "OPC_UA_PLC4X", "OPC_UA_MILO", "IEC104", "IEC61850", "IEC101",
            "DLT645_2007", "CUSTOM_TCP", "CUSTOM_UDP");

    @Test
    void allConnectionAdapterProvidersShouldBeDiscovered() {
        try (AnnotationConfigApplicationContext context = providerContext()) {
            assertEquals(19, context.getBeanNamesForType(ConnectionAdapterProvider.class).length);
            ConnectionFactory factory = factoryFromContext(context);

            assertEquals(EXPECTED_PROVIDER_TYPES, factory.registeredConnectionTypes());
        }
    }

    @Test
    void eachCanonicalConnectionTypeShouldResolveExactlyOneProvider() {
        ProtocolDescriptorRegistry registry = ProtocolDescriptorTestProviders.registry();
        Set<String> canonicalTypes = registry.primaryDescriptors().stream()
                .map(ProtocolDescriptor::connectionType)
                .filter(connectionType -> !"OPC_DA".equals(connectionType))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        canonicalTypes.add("TCP");

        ConnectionFactory factory = ConnectionAdapterTestProviders.factory();

        assertEquals(canonicalTypes, factory.registeredConnectionTypes());
        for (String connectionType : canonicalTypes) {
            assertNotNull(factory.providerFor(connectionType), connectionType);
        }
        assertNull(factory.providerFor("OPC_DA"));
    }

    @Test
    void duplicateConnectionTypeMustFailFast() {
        ConnectionAdapterProvider first = fakeProvider("MQTT");
        ConnectionAdapterProvider second = fakeProvider("mqtt");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new ConnectionFactory(ProtocolDescriptorTestProviders.registry(),
                        new ProtocolConnectionValidator(), List.of(first, second)));

        assertTrue(exception.getMessage().contains("MQTT"));
        assertTrue(exception.getMessage().contains(first.getClass().getName()));
        assertTrue(exception.getMessage().contains(second.getClass().getName()));
    }

    @Test
    void providerBlankOrEmptyConnectionTypeMustFailFast() {
        assertThrows(IllegalStateException.class,
                () -> new ConnectionFactory(ProtocolDescriptorTestProviders.registry(),
                        new ProtocolConnectionValidator(), List.of(fakeProvider())));
        assertThrows(IllegalStateException.class,
                () -> new ConnectionFactory(ProtocolDescriptorTestProviders.registry(),
                        new ProtocolConnectionValidator(), List.of(fakeProvider(" "))));
        assertThrows(IllegalStateException.class,
                () -> new ConnectionFactory(ProtocolDescriptorTestProviders.registry(),
                        new ProtocolConnectionValidator(), List.of(fakeProvider("CUSTOM-TCP", "CUSTOM_TCP"))));
    }

    @Test
    void unknownConnectionTypeMustFailAsBefore() {
        ConnectionFactory factory = ConnectionAdapterTestProviders.factory();

        CollectorException exception = assertThrows(CollectorException.class,
                () -> factory.createConnection(device("dev-unknown", "VENDOR_PRIVATE_PROTOCOL"),
                        new DeviceConnection()));

        assertEquals("不支持的连接类型: VENDOR_PRIVATE_PROTOCOL", exception.getMessage());
    }

    @Test
    void opcDaConnectionTypeMustRemainUnsupported() {
        ConnectionFactory factory = ConnectionAdapterTestProviders.factory();
        DeviceConnection connection = baseConnection("OPC_DA");
        connection.setExtJson(ext("bridgeMode", "INMEMORY"));

        CollectorException exception = assertThrows(CollectorException.class,
                () -> factory.createConnection(device("dev-opc-da", "OPC_DA"), connection));

        assertEquals("不支持的连接类型: OPC_DA", exception.getMessage());
    }

    @Test
    void aliasShouldResolveToCanonicalProvider() {
        ConnectionFactory factory = ConnectionAdapterTestProviders.factory();
        DeviceConnection https = new DeviceConnection();
        DeviceConnection mqttSsl = new DeviceConnection();
        DeviceConnection snmpV3 = new DeviceConnection();
        snmpV3.setExtJson(ext("snmpSecurityName", "collector", "snmpSecurityLevel", "noAuthNoPriv"));

        assertInstanceOf(HttpConnectionAdapter.class,
                factory.createConnection(device("dev-https", "HTTPS"), https));
        assertInstanceOf(MqttConnectionAdapter.class,
                factory.createConnection(device("dev-mqtt-ssl", "MQTT_SSL"), mqttSsl));
        assertInstanceOf(SnmpConnectionAdapter.class,
                factory.createConnection(device("dev-snmp-v3", "SNMP_V3"), snmpV3));
        assertEquals(443, https.getPort());
        assertEquals(8883, mqttSsl.getPort());
        assertEquals("3", snmpV3.getExtJson().get("snmpVersion"));
    }

    @Test
    void adapterClassCompatibility() {
        ConnectionFactory factory = ConnectionAdapterTestProviders.factory();

        expectedAdapters().forEach((connectionType, adapterClass) -> {
            DeviceConnection connection = validConnection(connectionType);
            ConnectionAdapter<?> adapter = factory.createConnection(device("dev-" + connectionType, connectionType),
                    connection);
            assertEquals(adapterClass, adapter.getClass(), connectionType);
        });
    }

    @Test
    void providerDependencyCompatibility() {
        CollectorProperties collectorProperties = new CollectorProperties();
        collectorProperties.getMqtt().setMaxConcurrentConnects(7);
        DeviceConnection mqttConnection = validConnection("MQTT");

        new MqttConnectionAdapterProvider(collectorProperties)
                .create("MQTT", device("dev-mqtt", "MQTT"), mqttConnection);

        assertEquals(7, mqttConnection.getExtJson().get("maxConcurrentConnects"));

        Executor ioExecutor = Runnable::run;
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.isShutdown()).thenReturn(false);

        WebSocketConnectionAdapter webSocketAdapter = (WebSocketConnectionAdapter)
                new WebSocketConnectionAdapterProvider(ioExecutor, scheduler)
                        .create("WEBSOCKET", device("dev-ws", "WEBSOCKET"), validConnection("WEBSOCKET"));
        assertSame(ioExecutor, ReflectionTestUtils.getField(webSocketAdapter, "httpExecutor"));
        assertSame(scheduler, ReflectionTestUtils.getField(webSocketAdapter, "heartbeatScheduler"));

        BacnetIpConnectionAdapter bacnetIpAdapter = (BacnetIpConnectionAdapter)
                new BacnetConnectionAdapterProvider(ioExecutor, scheduler)
                        .create("BACNET_IP", device("dev-bacnet-ip", "BACNET_IP"), validConnection("BACNET_IP"));
        assertSame(scheduler, ReflectionTestUtils.getField(bacnetIpAdapter, "protocolScheduler"));

        BacnetScConnectionAdapter bacnetScAdapter = (BacnetScConnectionAdapter)
                new BacnetConnectionAdapterProvider(ioExecutor, scheduler)
                        .create("BACNET_SC", device("dev-bacnet-sc", "BACNET_SC"), validConnection("BACNET_SC"));
        assertSame(ioExecutor, ReflectionTestUtils.getField(bacnetScAdapter, "httpExecutor"));
        assertSame(scheduler, ReflectionTestUtils.getField(bacnetScAdapter, "heartbeatScheduler"));

        SharedSerialChannelManager serialChannelManager = new SharedSerialChannelManager();
        Iec101ConnectionAdapter iec101Adapter = (Iec101ConnectionAdapter)
                new Iec101ConnectionAdapterProvider(serialChannelManager)
                        .create("IEC101", device("dev-iec101", "IEC101"), validConnection("IEC101"));
        Dlt645ConnectionAdapter dlt645Adapter = (Dlt645ConnectionAdapter)
                new Dlt645ConnectionAdapterProvider(serialChannelManager)
                        .create("DLT645_2007", device("dev-dlt645", "DLT645_2007"),
                                validConnection("DLT645_2007"));
        assertSame(serialChannelManager, ReflectionTestUtils.getField(iec101Adapter, "serialChannelManager"));
        assertSame(serialChannelManager, ReflectionTestUtils.getField(dlt645Adapter, "serialChannelManager"));
    }

    @Test
    void factoryMustNotDependOnConcreteAdapterImplementations() throws Exception {
        Path sourcePath = Path.of("../collector-runtime/src/main/java/com/wangbin/collector/core/connection/factory/ConnectionFactory.java");
        if (!Files.exists(sourcePath)) {
            sourcePath = Path.of("collector-runtime/src/main/java/com/wangbin/collector/core/connection/factory/ConnectionFactory.java");
        }
        String source = Files.readString(sourcePath, StandardCharsets.UTF_8);

        assertFalse(source.contains("case \""));
        assertFalse(source.contains("new TcpConnectionAdapter"));
        assertFalse(source.contains("Plc4xModbusTcpConnectionAdapter"));
        assertFalse(source.contains("BacnetIpConnectionAdapter"));
        assertFalse(source.contains("MqttConnectionAdapter"));
        assertFalse(source.contains("SharedSerialChannelManager"));
        assertFalse(source.contains("CollectorProperties"));
        assertFalse(source.contains("new ModbusConnectionAdapterProvider"));
    }

    private static AnnotationConfigApplicationContext providerContext() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(TestProviderDependencyConfig.class);
        context.scan("com.wangbin.collector.core.connection.factory.provider");
        context.refresh();
        return context;
    }

    private static ConnectionFactory factoryFromContext(AnnotationConfigApplicationContext context) {
        return new ConnectionFactory(ProtocolDescriptorTestProviders.registry(),
                new ProtocolConnectionValidator(),
                new ArrayList<>(context.getBeansOfType(ConnectionAdapterProvider.class).values()));
    }

    private static Map<String, Class<? extends ConnectionAdapter<?>>> expectedAdapters() {
        Map<String, Class<? extends ConnectionAdapter<?>>> adapters = new LinkedHashMap<>();
        adapters.put("TCP", TcpConnectionAdapter.class);
        adapters.put("HTTP", HttpConnectionAdapter.class);
        adapters.put("MQTT", MqttConnectionAdapter.class);
        adapters.put("WEBSOCKET", WebSocketConnectionAdapter.class);
        adapters.put("COAP", CoapConnectionAdapter.class);
        adapters.put("SIEMENS_S7", S7ConnectionAdapter.class);
        adapters.put("BACNET_IP", BacnetIpConnectionAdapter.class);
        adapters.put("BACNET_MSTP", BacnetMstpConnectionAdapter.class);
        adapters.put("BACNET_SC", BacnetScConnectionAdapter.class);
        adapters.put("MITSUBISHI_MC", MitsubishiMcConnectionAdapter.class);
        adapters.put("OMRON_FINS", OmronFinsUdpConnectionAdapter.class);
        adapters.put("ETHERNET_IP", EtherNetIpConnectionAdapter.class);
        adapters.put("ADS", AdsConnectionAdapter.class);
        adapters.put("KNXNET_IP", KnxNetIpConnectionAdapter.class);
        adapters.put("MODBUS_TCP", Plc4xModbusTcpConnectionAdapter.class);
        adapters.put("MODBUS_RTU", Plc4xModbusRtuConnectionAdapter.class);
        adapters.put("SNMP", SnmpConnectionAdapter.class);
        adapters.put("OPC_UA", Plc4xOpcUaConnectionAdapter.class);
        adapters.put("OPC_UA_PLC4X", Plc4xOpcUaConnectionAdapter.class);
        adapters.put("OPC_UA_MILO", OpcUaConnectionAdapter.class);
        adapters.put("IEC104", Iec104ConnectionAdapter.class);
        adapters.put("IEC61850", Iec61850ConnectionAdapter.class);
        adapters.put("IEC101", Iec101ConnectionAdapter.class);
        adapters.put("DLT645_2007", Dlt645ConnectionAdapter.class);
        adapters.put("CUSTOM_TCP", CustomTcpConnectionAdapter.class);
        adapters.put("CUSTOM_UDP", CustomUdpConnectionAdapter.class);
        return adapters;
    }

    private static DeviceConnection validConnection(String connectionType) {
        DeviceConnection connection = baseConnection(connectionType);
        Consumer<DeviceConnection> customizer = switch (connectionType) {
            case "OMRON_FINS" -> cfg -> cfg.setExtJson(ext("plcNode", 1, "localNode", 10));
            case "BACNET_IP" -> cfg -> cfg.setExtJson(ext("remoteDeviceInstance", 1001));
            case "BACNET_MSTP" -> cfg -> cfg.setExtJson(ext(
                    "serialPort", "COM1",
                    "baudRate", 38400,
                    "localMacAddress", 1,
                    "remoteMacAddress", 2,
                    "remoteDeviceInstance", 1001,
                    "apduTimeout", 5000,
                    "segmentTimeout", 2000,
                    "retries", 1,
                    "maxInfoFrames", 1,
                    "tokenClaimTimeoutMs", 1000,
                    "replyTimeoutMs", 1000,
                    "pollForMasterTimeoutMs", 250));
            case "BACNET_SC" -> cfg -> {
                cfg.setUrl("wss://127.0.0.1:443/bacnet/sc");
                cfg.setExtJson(ext(
                        "remoteDeviceInstance", 1001,
                        "keyStoreFile", temporaryPkcs12("bacnet-sc-client"),
                        "trustStoreFile", temporaryPkcs12("bacnet-sc-trust")));
            };
            case "ADS" -> cfg -> cfg.setExtJson(ext(
                    "targetAmsNetId", "1.2.3.4.1.1",
                    "targetAmsPort", 851,
                    "sourceAmsNetId", "1.2.3.4.1.2",
                    "sourceAmsPort", 30000));
            case "IEC101" -> cfg -> cfg.setExtJson(serialExt());
            case "DLT645_2007" -> cfg -> cfg.setExtJson(dlt645Ext());
            default -> cfg -> {
            };
        };
        customizer.accept(connection);
        return connection;
    }

    private static DeviceConnection baseConnection(String connectionType) {
        DeviceConnection connection = new DeviceConnection();
        connection.setConnectionType(connectionType);
        connection.setHost("127.0.0.1");
        connection.setPort(19000);
        connection.setUrl(null);
        return connection;
    }

    private static DeviceInfo device(String deviceId, String protocolType) {
        DeviceInfo deviceInfo = new DeviceInfo();
        deviceInfo.setDeviceId(deviceId);
        deviceInfo.setProtocolType(protocolType);
        deviceInfo.setIpAddress("127.0.0.1");
        return deviceInfo;
    }

    private static Map<String, Object> serialExt() {
        return ext(
                "serialPort", "COM1",
                "baudRate", 9600,
                "readTimeout", 3000,
                "writeTimeout", 3000);
    }

    private static Map<String, Object> dlt645Ext() {
        Map<String, Object> extJson = new LinkedHashMap<>(serialExt());
        extJson.put("meterAddress", "000000000001");
        return extJson;
    }

    private static String temporaryPkcs12(String prefix) {
        try {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            char[] password = new char[0];
            keyStore.load(null, password);
            Path path = Files.createTempFile(prefix, ".p12");
            try (var outputStream = Files.newOutputStream(path)) {
                keyStore.store(outputStream, password);
            }
            path.toFile().deleteOnExit();
            return path.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("创建测试 PKCS12 文件失败", exception);
        }
    }

    private static Map<String, Object> ext(Object... entries) {
        Map<String, Object> extJson = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            extJson.put(entries[i].toString(), entries[i + 1]);
        }
        return extJson;
    }

    private static ConnectionAdapterProvider fakeProvider(String... connectionTypes) {
        Set<String> supportedTypes = new LinkedHashSet<>(Arrays.asList(connectionTypes));
        return new ConnectionAdapterProvider() {
            @Override
            public Set<String> supportedConnectionTypes() {
                return supportedTypes;
            }

            @Override
            public ConnectionAdapter<?> create(String connectionType,
                                               DeviceInfo deviceInfo,
                                               DeviceConnection connectionConfig) {
                throw new UnsupportedOperationException("测试 Provider 不创建连接");
            }
        };
    }

    @Configuration
    static class TestProviderDependencyConfig {

        @Bean
        CollectorProperties collectorProperties() {
            return new CollectorProperties();
        }

        @Bean
        SharedSerialChannelManager sharedSerialChannelManager() {
            return new SharedSerialChannelManager();
        }

        @Bean("ioIntensiveExecutor")
        Executor ioIntensiveExecutor() {
            return Runnable::run;
        }

        @Bean("timeSliceScheduler")
        ScheduledExecutorService timeSliceScheduler() {
            ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
            when(scheduler.isShutdown()).thenReturn(false);
            return scheduler;
        }
    }
}
