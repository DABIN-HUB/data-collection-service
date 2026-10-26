package com.wangbin.collector.core.config.manager;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.config.model.DeviceContext;
import com.wangbin.collector.core.config.persistence.ConnectionConfiguration;
import com.wangbin.collector.core.config.persistence.DeviceConfiguration;
import com.wangbin.collector.core.config.persistence.PointConfiguration;
import com.wangbin.collector.core.config.persistence.StoredDeviceConfiguration;
import com.wangbin.collector.core.config.store.LocalDeviceConfigStore;
import com.wangbin.collector.core.report.validator.FieldUniquenessValidator;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证配置管理器恢复选择与三版本隔离；真实SQLite映射/文件边界另由适配器集成测试覆盖。 */
class WangbinConfigManagerRecoveryTest {
    @Test
    void legacyConstructorStillRestoresJsonWithoutAnSqliteDependency() {
        var sync = mock(ConfigSyncService.class);
        var legacy = mock(LocalDeviceConfigStore.class);
        var publisher = mock(ApplicationEventPublisher.class);
        DeviceContext local = context("wangbin-legacy", "wangbin-legacy-point");
        when(legacy.load()).thenReturn(List.of(local));
        when(sync.loadAllDevices()).thenReturn(List.of());
        var manager = new ConfigManager(sync, publisher, new FieldUniquenessValidator(), legacy);
        manager.init();
        assertEquals(local.getDeviceId(), manager.getDevice(local.getDeviceId()).getDeviceId());
        verify(legacy).load();
        verify(legacy, never()).save(anyList());
        verifyNoInteractions(publisher);
    }

    @Test
    void sqliteRecoveryDoesNotReadJsonOrPublishStartupEventsAndSeparatesVersions() {
        DeviceContext local = context("wangbin-restored", "wangbin-restored-point");
        var sync = mock(ConfigSyncService.class);
        var legacy = mock(LocalDeviceConfigStore.class);
        var publisher = mock(ApplicationEventPublisher.class);
        when(sync.loadAllDevices()).thenReturn(List.of());
        ReflectionTestUtils.setField(sync, "sourceVersion", "wangbin-source-v9");
        when(sync.getSourceVersion()).thenReturn("wangbin-source-v9");
        var manager = new ConfigManager(sync, publisher, new FieldUniquenessValidator(), legacy);
        installBridge(manager, List.of(local), null);
        manager.init();
        assertEquals(local.getDeviceInfo(), manager.getDevice(local.getDeviceId()));
        assertEquals(local.getDataPoints().get(0).getPointId(), manager.getDataPoints(local.getDeviceId()).get(0).getPointId());
        Map<?, ?> revisions = (Map<?, ?>) ReflectionTestUtils.getField(manager, "localConfigurationRevisions");
        assertNotNull(revisions);
        assertEquals(77L, revisions.get(local.getDeviceId()));
        long version = manager.getDeviceConfigVersion(local.getDeviceId());
        assertTrue(version > 0);
        assertNotEquals(77L, version);
        assertEquals(version, manager.getDeviceConfigurationSnapshot(local.getDeviceId()).configVersion());
        assertEquals("wangbin-source-v9", sync.getSourceVersion());
        verifyNoInteractions(legacy, publisher);
        verify(sync).startSyncTask();
        verify(sync).registerConfigListener(any());
    }

    @Test
    void invalidMigrationSourceFailsBeforeAnyCacheOrJsonFallback() {
        var sync = mock(ConfigSyncService.class);
        var legacy = mock(LocalDeviceConfigStore.class);
        var publisher = mock(ApplicationEventPublisher.class);
        var manager = new ConfigManager(sync, publisher, new FieldUniquenessValidator(), legacy);
        installBridge(manager, List.of(), new IllegalStateException("wangbin迁移源摘要不一致"));
        assertThrows(IllegalStateException.class, manager::init);
        assertTrue(manager.getAllDevices().isEmpty());
        assertEquals(0L, manager.getDeviceConfigVersion("wangbin-restored"));
        verifyNoInteractions(legacy, publisher, sync);
    }

    @Test
    void duplicateGlobalPointIdsRejectTheWholeRecoveryWithoutPartialCache() {
        var sync = mock(ConfigSyncService.class);
        var legacy = mock(LocalDeviceConfigStore.class);
        var publisher = mock(ApplicationEventPublisher.class);
        var manager = new ConfigManager(sync, publisher, new FieldUniquenessValidator(), legacy);
        installBridge(manager, List.of(context("wangbin-first", "wangbin-shared-point"),
                context("wangbin-second", "wangbin-shared-point")), null);
        assertThrows(IllegalStateException.class, manager::init);
        assertTrue(manager.getAllDevices().isEmpty());
        assertTrue(manager.getAllDeviceContexts().isEmpty());
        verifyNoInteractions(legacy, publisher, sync);
    }

    @Test
    void providerIdentityCollisionCannotReplaceRestoredLocalOwnership() {
        DeviceContext local = context("wangbin-owned", "wangbin-owned-point");
        var sync = mock(ConfigSyncService.class);
        var legacy = mock(LocalDeviceConfigStore.class);
        var publisher = mock(ApplicationEventPublisher.class);
        DeviceInfo remote = new DeviceInfo();
        remote.setDeviceId(local.getDeviceId());
        remote.setDeviceName("远端同名配置");
        remote.setConfigSource("remote");
        when(sync.loadAllDevices()).thenReturn(List.of(remote));
        var manager = new ConfigManager(sync, publisher, new FieldUniquenessValidator(), legacy);
        installBridge(manager, List.of(local), null);
        manager.init();
        assertEquals("local", manager.getDevice(local.getDeviceId()).getConfigSource());
        assertEquals(local.getDeviceInfo().getDeviceName(), manager.getDevice(local.getDeviceId()).getDeviceName());
        verify(sync, never()).loadDataPoints(local.getDeviceId());
        verifyNoInteractions(legacy, publisher);
    }

    private static void installBridge(ConfigManager manager, List<DeviceContext> contexts, RuntimeException failure) {
        Field field = ReflectionUtils.findField(ConfigManager.class, "localConfigurationPersistence");
        assertNotNull(field, "ConfigManager尚未接入SQLite恢复Port");
        Class<?> bridgeType = field.getType();
        var stored = contexts.stream().map(context -> new StoredDeviceConfiguration(configuration(context), 77L)).toList();
        Object bridge = Proxy.newProxyInstance(bridgeType.getClassLoader(), new Class<?>[] {bridgeType}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "restore" -> {
                    if (failure != null) throw failure;
                    yield stored;
                }
                case "toRuntime" -> contexts.stream().filter(context -> context.getDeviceId().equals(
                        ((DeviceConfiguration) args[0]).deviceId())).findFirst().orElseThrow();
                case "findDevice" -> stored.stream().filter(item -> item.configuration().deviceId().equals(args[0])).findFirst();
                case "equivalent" -> equivalent((DeviceContext) args[0], (DeviceContext) args[1]);
                case "toString" -> "wangbin恢复测试桥接";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException("恢复测试不执行持久写入");
            };
        });
        ReflectionTestUtils.setField(manager, field.getName(), bridge);
    }

    private static boolean equivalent(DeviceContext left, DeviceContext right) {
        if (left == null || right == null) return left == right;
        return Objects.equals(left.getDeviceInfo(), right.getDeviceInfo())
                && Objects.equals(left.getConnectionConfig(), right.getConnectionConfig())
                && Objects.equals(left.getDataPoints(), right.getDataPoints());
    }

    private static DeviceConfiguration configuration(DeviceContext context) {
        String id = context.getDeviceId();
        String point = context.getDataPoints().get(0).getPointId();
        return new DeviceConfiguration(id,
                "{\"deviceId\":\"" + id + "\",\"configSource\":\"local\",\"temporaryConfig\":true}",
                new ConnectionConfiguration(id, "{\"deviceId\":\"" + id + "\"}"),
                List.of(new PointConfiguration(id, point, 0, 1,
                        "{\"deviceId\":\"" + id + "\",\"pointId\":\"" + point + "\",\"status\":1}")));
    }

    private static DeviceContext context(String id, String pointId) {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId(id);
        device.setDeviceName("恢复测试设备");
        device.setProtocolType("MODBUS_TCP");
        device.setConnectionType("MODBUS_TCP");
        device.setIpAddress("127.0.0.1");
        device.setPort(1502);
        device.setConfigSource("local");
        device.setTemporaryConfig(true);
        DeviceConnection connection = new DeviceConnection();
        connection.setDeviceId(id);
        connection.setConnectionType("MODBUS_TCP");
        connection.setHost("127.0.0.1");
        connection.setPort(1502);
        DataPoint point = new DataPoint();
        point.setDeviceId(id);
        point.setPointId(pointId);
        point.setPointCode("temperature");
        point.setAddress("40001");
        point.setDataType("FLOAT");
        point.setReadWrite("R");
        point.setStatus(1);
        point.setBaseCollectionInterval(2000L);
        point.setMinCollectionInterval(1000L);
        point.setMaxCollectionInterval(30000L);
        point.setPointChangeThreshold(0.1D);
        return DeviceContext.of(device, connection, List.of(point));
    }
}
