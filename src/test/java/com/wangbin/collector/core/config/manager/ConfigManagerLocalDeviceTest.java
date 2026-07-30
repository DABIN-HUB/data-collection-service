package com.wangbin.collector.core.config.manager;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.config.model.ConfigUpdateEvent;
import com.wangbin.collector.core.config.model.ConfigUpdateType;
import com.wangbin.collector.core.config.model.DeviceContext;
import com.wangbin.collector.core.config.validator.ProtocolConnectionValidator;
import com.wangbin.collector.core.report.validator.FieldUniquenessValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfigManagerLocalDeviceTest {

    private ConfigManager configManager;
    private ConfigSyncService configSyncService;
    private ApplicationEventPublisher eventPublisher;

    @BeforeEach
    void setUp() {
        configManager = new ConfigManager();
        configSyncService = mock(ConfigSyncService.class);
        ReflectionTestUtils.setField(configManager, "configSyncService", configSyncService);
        eventPublisher = mock(ApplicationEventPublisher.class);
        ReflectionTestUtils.setField(configManager, "eventPublisher", eventPublisher);
        ReflectionTestUtils.setField(configManager, "fieldUniquenessValidator", new FieldUniquenessValidator());
        ReflectionTestUtils.setField(configManager, "protocolConnectionValidator", new ProtocolConnectionValidator());
    }

    @Test
    void shouldMarkDeviceConnectionAndPointsAsLocalTemporary() {
        boolean saved = configManager.saveLocalDeviceConfig(
                device("local-1"),
                connection("local-1"),
                List.of(point("local-1")),
                false);

        assertTrue(saved);
        assertTrue(configManager.isLocalTemporaryDevice("local-1"));
        assertEquals(ConfigManager.CONFIG_SOURCE_LOCAL, configManager.getDevice("local-1").getConfigSource());
        assertEquals(Boolean.TRUE, configManager.getDevice("local-1").getTemporaryConfig());
        assertEquals(ConfigManager.CONFIG_SOURCE_LOCAL,
                configManager.getConnectionConfig("local-1").getExtJson().get(ConfigManager.CONFIG_SOURCE_KEY));
        DataPoint savedPoint = configManager.getDataPoints("local-1").get(0);
        assertEquals(ConfigManager.CONFIG_SOURCE_LOCAL,
                savedPoint.getAdditionalConfig().get(ConfigManager.CONFIG_SOURCE_KEY));
        assertEquals(2000L, savedPoint.getBaseCollectionInterval());
        assertEquals(0L, savedPoint.getCurrentCollectionInterval());
        assertEquals(100L, savedPoint.getMinCollectionInterval());
        assertEquals(3600000L, savedPoint.getMaxCollectionInterval());
        assertEquals(1.0, savedPoint.getPointChangeThreshold());
    }

    @Test
    void shouldRefuseToOverwriteNonLocalDevice() {
        assertTrue(configManager.updateDeviceConfig(device("remote-1")));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> configManager.saveLocalDeviceConfig(
                        device("remote-1"),
                        connection("remote-1"),
                        List.of(point("remote-1")),
                        true));

        assertTrue(error.getMessage().contains("non-local"));
    }

    @Test
    void shouldDeleteOnlyLocalTemporaryDevice() {
        configManager.saveLocalDeviceConfig(
                device("local-delete"),
                connection("local-delete"),
                List.of(point("local-delete")),
                false);

        assertTrue(configManager.deleteLocalDeviceConfig("local-delete"));
        assertFalse(configManager.containsDevice("local-delete"));
    }

    @Test
    void shouldKeepLocalTemporaryDeviceAfterFullRemoteReload() {
        when(configSyncService.loadAllDevices()).thenReturn(List.of());
        configManager.saveLocalDeviceConfig(
                device("local-keep"),
                connection("local-keep"),
                List.of(point("local-keep")),
                false);

        ReflectionTestUtils.invokeMethod(configManager, "loadAllConfig");

        assertTrue(configManager.containsDevice("local-keep"));
        assertTrue(configManager.isLocalTemporaryDevice("local-keep"));
    }

    @Test
    void shouldRemoveConnectionCacheWhenRemoteConnectionIsDeleted() {
        assertTrue(configManager.updateDeviceConfig(device("remote-1")));
        assertTrue(configManager.updateConnectionConfig("remote-1", connection("remote-1")));
        when(configSyncService.loadConnectionConfig("remote-1")).thenReturn(null);

        ReflectionTestUtils.invokeMethod(configManager, "handleConfigChange",
                ConfigUpdateEvent.createConnectionUpdateEvent("remote-1"));

        assertNull(configManager.getConnectionConfig("remote-1"));
    }

    @Test
    void shouldPublishOnlyOneEventAfterAtomicImport() {
        DeviceInfo importedDevice = device("import-1");
        boolean imported = configManager.replaceDeviceContextsAtomically(List.of(
                DeviceContext.of(importedDevice, connection("import-1"), List.of(point("import-1")))));

        assertTrue(imported);
        verify(eventPublisher, times(1)).publishEvent(argThat((Object event) ->
                event instanceof ConfigUpdateEvent updateEvent
                        && ConfigUpdateType.ALL.getValue().equals(updateEvent.getConfigType())));
    }

    @Test
    void shouldKeepOriginalConfigWhenAtomicImportValidationFails() {
        DeviceInfo original = device("stable-1");
        original.setDeviceName("原设备");
        assertTrue(configManager.updateDeviceConfig(original));

        DeviceInfo replacement = device("stable-1");
        replacement.setDeviceName("错误替换设备");
        boolean imported = configManager.replaceDeviceContextsAtomically(List.of(
                DeviceContext.of(replacement, connection("stable-1"), List.of(point("stable-1"))),
                DeviceContext.of(null, null, List.of())));

        assertFalse(imported);
        assertEquals("原设备", configManager.getDevice("stable-1").getDeviceName());
    }

    private DeviceInfo device(String deviceId) {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId(deviceId);
        device.setDeviceName("test-device");
        device.setProtocolType("MODBUS_TCP");
        device.setCollectionInterval(2000);
        return device;
    }

    private DeviceConnection connection(String deviceId) {
        DeviceConnection connection = new DeviceConnection();
        connection.setDeviceId(deviceId);
        connection.setConnectionType("MODBUS_TCP");
        connection.setHost("127.0.0.1");
        connection.setPort(502);
        return connection;
    }

    private DataPoint point(String deviceId) {
        DataPoint point = new DataPoint();
        point.setDeviceId(deviceId);
        point.setPointCode("temperature");
        point.setAddress("40001");
        point.setDataType("FLOAT");
        return point;
    }
}
