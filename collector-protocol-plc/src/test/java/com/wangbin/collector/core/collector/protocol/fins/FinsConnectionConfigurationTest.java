package com.wangbin.collector.core.collector.protocol.fins;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.domain.enums.FinsTransportMode;
import com.wangbin.collector.core.collector.protocol.fins.domain.FinsConnectionConfig;
import com.wangbin.collector.core.config.protocol.OmronProtocolDescriptorProvider;
import com.wangbin.collector.core.config.protocol.ProtocolDescriptorRegistry;
import com.wangbin.collector.core.config.protocol.ProtocolFieldConfig;
import com.wangbin.collector.core.config.validator.ProtocolConnectionValidator;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FinsConnectionConfigurationTest {
    private final ProtocolConnectionValidator validator = new ProtocolConnectionValidator();

    @Test
    void transportModesAndLegacyNullAgreeAcrossParserAndValidator() {
        for (Map.Entry<String, FinsTransportMode> entry : Map.of(
                "UDP", FinsTransportMode.UDP, "TCP", FinsTransportMode.TCP,
                "AUTO", FinsTransportMode.AUTO, " tcp ", FinsTransportMode.TCP).entrySet()) {
            DeviceConnection connection = connection();
            config(connection, "transport", entry.getKey());
            validator.validate(device(), connection);
            assertEquals(entry.getValue(), FinsConnectionConfig.from(connection).getTransport());
        }
        DeviceConnection legacy = connection();
        validator.validate(device(), legacy);
        assertEquals(FinsTransportMode.AUTO, FinsConnectionConfig.from(legacy).getTransport());
    }

    @Test
    void unknownTransportFailsBeforeConnectionForParserAndRuntimeValidator() {
        DeviceConnection connection = connection();
        config(connection, "transport", "SERIAL");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> FinsConnectionConfig.from(connection))
                .getMessage().contains("transport"));
        assertTrue(assertThrows(RuntimeException.class, () -> validator.validate(device(), connection))
                .getMessage().contains("transport"));
        assertFalse(validator.isValid(device(), connection));
    }

    @Test
    void connectionAndRequestTimeoutsRemainIndependent() {
        DeviceConnection connection = connection();
        connection.setConnectTimeout(73);
        connection.setReadTimeout(910);
        connection.setTimeout(2100);
        validator.validate(device(), connection);
        FinsConnectionConfig parsed = FinsConnectionConfig.from(connection);
        assertEquals(73, parsed.getConnectTimeoutMs());
        assertEquals(910, parsed.getTimeoutMs());
        connection.setReadTimeout(null);
        assertEquals(2100, FinsConnectionConfig.from(connection).getTimeoutMs());
        connection.setConnectTimeout(0);
        assertThrows(RuntimeException.class, () -> validator.validate(device(), connection));
        assertThrows(IllegalArgumentException.class, () -> FinsConnectionConfig.from(connection));
    }

    @Test
    void frameSizeBoundsAndBufferAreDistinct() {
        DeviceConnection connection = connection();
        connection.setBufferSize(512);
        for (int value : new int[]{34, 4096, 1_048_576}) {
            config(connection, "maxFrameSize", value);
            validator.validate(device(), connection);
            assertEquals(value, FinsConnectionConfig.from(connection).getMaxFrameSize());
            assertEquals(512, FinsConnectionConfig.from(connection).getReceiveBufferSize());
        }
        for (int value : new int[]{24, 33, 1_048_577}) {
            config(connection, "maxFrameSize", value);
            assertThrows(RuntimeException.class, () -> validator.validate(device(), connection));
            assertThrows(IllegalArgumentException.class, () -> FinsConnectionConfig.from(connection));
        }
    }

    @Test
    void negotiatedNodeSnapshotDoesNotMutateOriginalConfiguration() {
        DeviceConnection connection = connection();
        config(connection, "transport", "TCP");
        FinsConnectionConfig original = FinsConnectionConfig.from(connection);
        FinsConnectionConfig effective = original.withEffectiveNodes(37, 52);
        assertEquals(10, original.getLocalNode());
        assertEquals(1, original.getPlcNode());
        assertEquals(37, effective.getLocalNode());
        assertEquals(52, effective.getPlcNode());
        assertEquals(original.getConnectTimeoutMs(), effective.getConnectTimeoutMs());
        assertEquals(original.getTimeoutMs(), effective.getTimeoutMs());
        assertEquals(original.getMaxFrameSize(), effective.getMaxFrameSize());
        assertEquals(10, FinsConnectionConfig.from(connection).getLocalNode());
    }

    @Test
    void descriptorTransportTimeoutAndFrameSettingsMatchRuntimeContract() {
        ProtocolDescriptorRegistry registry = new ProtocolDescriptorRegistry(List.of(new OmronProtocolDescriptorProvider()));
        Map<String, ProtocolFieldConfig> fields = new LinkedHashMap<>();
        registry.toSchema("OMRON_FINS").getConnectionFields().forEach(field -> fields.put(field.getName(), field));
        assertEquals(List.of("UDP", "TCP", "AUTO"), fields.get("transport").getOptions());
        assertEquals("AUTO", fields.get("transport").getDefaultValue());
        assertEquals("5000", fields.get("connectTimeoutMs").getDefaultValue());
        assertEquals("30000", fields.get("readTimeoutMs").getDefaultValue());
        assertEquals("8192", fields.get("maxFrameSize").getDefaultValue());
        assertEquals("8192", fields.get("bufferSize").getDefaultValue());
        assertNotEquals(fields.get("connectTimeoutMs").getName(), fields.get("readTimeoutMs").getName());
        assertTrue(fields.get("transport").getDescription().contains("UDP"));
        assertTrue(fields.get("transport").getDescription().contains("TCP"));
        assertTrue(fields.get("maxFrameSize").getDescription().contains("1048576"));
        assertTrue(fields.get("maxFrameSize").getDescription().contains("34"));
        assertEquals(8192, FinsConnectionConfig.from(connection()).getMaxFrameSize());
    }

    private static DeviceInfo device() {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("fins-config-test");
        device.setProtocolType("OMRON_FINS");
        device.setIpAddress("127.0.0.1");
        device.setPort(9600);
        return device;
    }

    private static DeviceConnection connection() {
        DeviceConnection connection = new DeviceConnection();
        connection.setConnectionType("OMRON_FINS");
        connection.setHost("127.0.0.1");
        connection.setPort(9600);
        connection.setExtJson(new LinkedHashMap<>(Map.of("plcNode", 1, "localNode", 10)));
        return connection;
    }

    private static void config(DeviceConnection connection, String key, Object value) {
        connection.getExtJson().put(key, value);
    }
}
