package com.wangbin.collector.core.config.manager;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.config.model.ConfigUpdateEvent;
import com.wangbin.collector.core.config.model.ConfigUpdateType;
import com.wangbin.collector.core.config.model.DeviceContext;
import com.wangbin.collector.core.report.validator.FieldUniquenessValidator;
import com.wangbin.collector.core.config.validator.ProtocolPointValidator;
import com.wangbin.collector.core.config.store.LocalDeviceConfigStore;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfigManagerLocalDeviceTest {

    private ConfigManager configManager;
    private ConfigSyncService configSyncService;
    private ApplicationEventPublisher eventPublisher;

    @BeforeEach
    void setUp() {
        configSyncService = mock(ConfigSyncService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        configManager = new ConfigManager(
                configSyncService,
                eventPublisher,
                new FieldUniquenessValidator(),
                null);
    }

    @Test
    void unleasedDeletionTombstonesMustNotAccumulate() {
        for (int index = 0; index < 32; index++) {
            String deviceId = "unleased-delete-" + index;
            assertTrue(configManager.saveLocalDeviceConfig(device(deviceId), connection(deviceId), List.of(point(deviceId)), false));
            assertTrue(configManager.deleteLocalDeviceConfig(deviceId));
            assertEquals(0L, configManager.getDeviceConfigVersion(deviceId));
        }
        assertTrue(((Map<?, ?>) ReflectionTestUtils.getField(configManager, "deletedConfigurationVersions")).isEmpty());
        assertTrue(((Map<?, ?>) ReflectionTestUtils.getField(configManager, "deviceConfigVersions")).isEmpty());
    }

    @Test
    void newWritesMustRejectMissingPointIdentityWithoutAdvancingVersion() {
        String deviceId = "missing-point-id";
        assertTrue(configManager.saveLocalDeviceConfig(device(deviceId), connection(deviceId), List.of(point(deviceId)), false));
        long version = configManager.getDeviceConfigVersion(deviceId);
        DataPoint missing = point(deviceId);
        missing.setPointId(null);
        assertFalse(configManager.updateDataPoints(deviceId, List.of(missing)));
        assertThrows(IllegalArgumentException.class, () -> configManager.saveLocalDeviceConfig(
                device(deviceId), connection(deviceId), List.of(missing), true));
        assertThrows(IllegalArgumentException.class, () -> configManager.replaceDeviceContextAtomically(
                deviceId, configManager.getDevice(deviceId), connection(deviceId), List.of(missing), version));
        assertEquals(version, configManager.getDeviceConfigVersion(deviceId));
        assertEquals(deviceId + ":temperature", configManager.getDataPoints(deviceId).get(0).getPointId());
    }

    @Test
    void deletionTombstoneMustLiveThroughPublicationAndPendingConsumerThenBeReleased() {
        String deviceId = "leased-delete";
        assertTrue(configManager.saveLocalDeviceConfig(device(deviceId), connection(deviceId), List.of(point(deviceId)), false));
        AtomicReference<Runnable> release = new AtomicReference<>();
        AtomicReference<Long> deletedVersion = new AtomicReference<>();
        doAnswer(invocation -> {
            ConfigUpdateEvent event = invocation.getArgument(0);
            if ("local-delete".equals(event.getConfigType())) {
                deletedVersion.set(event.getConfigVersion());
                release.set(configManager.retainDeletedConfigurationVersion(deviceId, event.getConfigVersion()));
                assertNotNull(release.get());
                assertTrue(configManager.runIfConfigurationCurrent(deviceId, event.getConfigVersion(), () -> {}));
            }
            return null;
        }).when(eventPublisher).publishEvent(org.mockito.ArgumentMatchers.any(ConfigUpdateEvent.class));

        assertTrue(configManager.deleteLocalDeviceConfig(deviceId));
        assertEquals(deletedVersion.get().longValue(), configManager.getDeviceConfigVersion(deviceId));
        release.get().run();
        release.get().run();
        assertEquals(0L, configManager.getDeviceConfigVersion(deviceId));
        assertFalse(configManager.runIfConfigurationCurrent(deviceId, deletedVersion.get(), () -> {
            throw new AssertionError("旧删除不得执行");
        }));
        assertTrue(configManager.saveLocalDeviceConfig(device(deviceId), connection(deviceId), List.of(point(deviceId)), false));
        assertTrue(configManager.getDeviceConfigVersion(deviceId) > deletedVersion.get());
    }

    @Test
    void bundleSnapshotMustDetachNestedValuesAndPreserveAlarmAndHistoricIds() {
        String deviceId = "detached-bundle";
        DataPoint original = point(deviceId);
        original.setId(99L);
        original.setAlarmRule("[{\"ruleId\":\"original\",\"threshold\":10}]");
        original.setAdditionalConfig(new java.util.LinkedHashMap<>(Map.of("nested", new java.util.LinkedHashMap<>(Map.of("value", "original")))));
        assertTrue(configManager.saveLocalDeviceConfig(device(deviceId), connection(deviceId), List.of(original), false));
        ConfigManager.DeviceConfigurationSnapshot snapshot = configManager.getDeviceConfigurationSnapshot(deviceId);
        DataPoint detached = snapshot.context().getDataPoints().get(0);
        assertEquals(99L, detached.getId());
        assertEquals(original.getPointId(), detached.getPointId());
        assertEquals("original", detached.getAlarmRule().get(0).getRuleId());
        ((Map<String, Object>) detached.getAdditionalConfig().get("nested")).put("value", "changed");
        detached.setAddress("40009");
        snapshot.context().getDeviceInfo().setDeviceName("changed");
        assertEquals("original", ((Map<?, ?>) configManager.getDataPoints(deviceId).get(0).getAdditionalConfig().get("nested")).get("value"));
        assertEquals("40001", configManager.getDataPoints(deviceId).get(0).getAddress());
        assertEquals(snapshot.configVersion(), configManager.getDeviceConfigVersion(deviceId));
    }

    @Test
    void changedPointIdentityEventMustContainOnlyRetiredIdsAndCloneVersions() {
        String deviceId = "retired-identity";
        assertTrue(configManager.saveLocalDeviceConfig(device(deviceId), connection(deviceId), List.of(point(deviceId)), false));
        long previous = configManager.getDeviceConfigVersion(deviceId);
        DataPoint changed = point(deviceId);
        changed.setAddress("40002");
        assertTrue(configManager.updateDataPoints(deviceId, List.of(changed)));
        verify(eventPublisher).publishEvent(argThat((Object value) -> {
            if (!(value instanceof ConfigUpdateEvent event) || !"points".equals(event.getConfigType())) return false;
            ConfigUpdateEvent copy = event.clone();
            return copy.getPreviousVersion() == previous
                    && copy.getConfigVersion().equals(configManager.getDeviceConfigVersion(deviceId))
                    && copy.getRetiredPointIds().equals(Set.of(changed.getPointId()));
        }));
    }

    @Test
    void shouldRejectForeignAndDuplicatePointIdentitiesBeforeLegacyWrite() {
        assertTrue(configManager.saveLocalDeviceConfig(device("identity"), connection("identity"), List.of(point("identity")), false));
        long version = configManager.getDeviceConfigVersion("identity");
        DataPoint foreign = point("other");
        assertFalse(configManager.updateDataPoints("identity", List.of(foreign)));
        DataPoint duplicate = point("identity");
        duplicate.setPointCode("different-code");
        assertFalse(configManager.updateDataPoints("identity", List.of(point("identity"), duplicate)));
        assertEquals(version, configManager.getDeviceConfigVersion("identity"));
    }

    @Test
    void shouldNotRestoreRemovedDeviceFromConcurrentFullRefresh() throws Exception {
        assertTrue(configManager.updateDeviceConfig(device("removed")));
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        when(configSyncService.loadAllDevices()).thenAnswer(invocation -> {
            reading.countDown();
            assertTrue(resume.await(5, TimeUnit.SECONDS));
            return List.of(device("removed"));
        });
        CompletableFuture<Void> refresh = CompletableFuture.runAsync(() ->
                ReflectionTestUtils.invokeMethod(configManager, "loadAllConfig"));
        try {
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            assertTrue(configManager.clearDeviceConfig("removed"));
        } finally {
            resume.countDown();
        }
        refresh.get(5, TimeUnit.SECONDS);
        assertFalse(configManager.containsDevice("removed"));
    }

    @Test
    void shouldNotOverwriteConcurrentConnectionReload() throws Exception {
        assertTrue(configManager.updateDeviceConfig(device("reloaded")));
        DeviceConnection changed = connection("reloaded");
        changed.setHost("192.0.2.15");
        when(configSyncService.loadConnectionConfig("reloaded")).thenReturn(changed);
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        when(configSyncService.loadAllDevices()).thenAnswer(invocation -> {
            reading.countDown();
            assertTrue(resume.await(5, TimeUnit.SECONDS));
            return List.of(device("reloaded"));
        });
        CompletableFuture<Void> refresh = CompletableFuture.runAsync(() ->
                ReflectionTestUtils.invokeMethod(configManager, "loadAllConfig"));
        try {
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            ReflectionTestUtils.invokeMethod(configManager, "reloadConnectionConfig", "reloaded");
        } finally {
            resume.countDown();
        }
        refresh.get(5, TimeUnit.SECONDS);
        assertEquals("192.0.2.15", configManager.getConnectionConfig("reloaded").getHost());
    }

    @Test
    void shouldKeepCommittedBundleAndLegacyUpdateAfterEventFailure() {
        assertTrue(configManager.updateDeviceConfig(device("event-failure")));
        long version = configManager.getDeviceConfigVersion("event-failure");
        doThrow(new IllegalStateException("listener failed")).when(eventPublisher).publishEvent(org.mockito.ArgumentMatchers.any(ConfigUpdateEvent.class));
        DeviceInfo replacement = device("event-failure");
        replacement.setDeviceName("committed");
        assertTrue(configManager.updateDeviceConfig(replacement));
        assertEquals("committed", configManager.getDevice("event-failure").getDeviceName());
        long nextVersion = configManager.getDeviceConfigVersion("event-failure");
        assertTrue(nextVersion > version);
        DeviceInfo bundleDevice = device("event-failure");
        bundleDevice.setDeviceName("bundle committed");
        configManager.replaceDeviceContextAtomically("event-failure", bundleDevice, null, List.of(), nextVersion);
        assertEquals("bundle committed", configManager.getDevice("event-failure").getDeviceName());
        assertTrue(configManager.getDeviceConfigVersion("event-failure") > nextVersion);
    }

    @Test
    void shouldDefaultNewMqttPointsToSubscribeWithoutChangingExplicitMode() {
        DeviceInfo mqtt = device("mqtt-local");
        mqtt.setProtocolType("MQTT");
        DeviceConnection broker = connection("mqtt-local");
        broker.setConnectionType("MQTT");
        DataPoint defaultPoint = point("mqtt-local");
        defaultPoint.setCollectionMode(null);
        assertTrue(configManager.saveLocalDeviceConfig(mqtt, broker, List.of(defaultPoint), false));
        assertEquals("SUBSCRIPTION", configManager.getDataPoints("mqtt-local").get(0).getCollectionMode());
        assertNull(defaultPoint.getCollectionMode());

        DeviceInfo second = device("mqtt-explicit");
        second.setProtocolType("MQTT");
        DataPoint polling = point("mqtt-explicit");
        polling.setCollectionMode("POLLING");
        assertTrue(configManager.saveLocalDeviceConfig(second, connection("mqtt-explicit"),
                List.of(polling), false));
        assertEquals("POLLING", configManager.getDataPoints("mqtt-explicit").get(0).getCollectionMode());
    }

    @Test
    void shouldDefaultImportedMqttPointToSubscribe() {
        DeviceInfo mqtt = device("mqtt-import");
        mqtt.setProtocolType("MQTT");
        DataPoint point = point("mqtt-import");
        point.setCollectionMode(null);
        assertTrue(configManager.replaceDeviceContextsAtomically(List.of(
                DeviceContext.of(mqtt, connection("mqtt-import"), List.of(point)))));
        assertEquals("SUBSCRIPTION", configManager.getDataPoints("mqtt-import").get(0).getCollectionMode());
    }

    @Test
    void shouldRollBackLocalPartialUpdateWhenPersistenceFails() {
        LocalDeviceConfigStore store = mock(LocalDeviceConfigStore.class);
        ConfigManager manager = new ConfigManager(configSyncService, eventPublisher,
                new FieldUniquenessValidator(), store);
        assertTrue(manager.saveLocalDeviceConfig(device("rollback"), connection("rollback"),
                List.of(point("rollback")), false));
        long oldVersion = manager.getDeviceConfigVersion("rollback");
        DeviceContext oldContext = manager.getDeviceContext("rollback");
        DataPoint replacement = point("rollback");
        replacement.setAddress("40002");
        doThrow(new IllegalStateException("磁盘不可用")).when(store).save(org.mockito.ArgumentMatchers.anyList());

        assertFalse(manager.updateDataPoints("rollback", List.of(replacement)));
        assertEquals("40001", manager.getDataPoints("rollback").get(0).getAddress());
        assertSame(oldContext, manager.getDeviceContext("rollback"));
        assertEquals(oldVersion, manager.getDeviceConfigVersion("rollback"));
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
    void shouldPreserveAllLiveCachesAndVersionWhenCandidatePointLoadFails() {
        DeviceInfo original = device("stable-1");
        assertTrue(configManager.updateDeviceConfig(original));
        assertTrue(configManager.updateConnectionConfig("stable-1", connection("stable-1")));
        assertTrue(configManager.updateDataPoints("stable-1", List.of(point("stable-1"))));
        long originalVersion = configManager.getDeviceConfigVersion("stable-1");
        DeviceInfo originalDevice = configManager.getDevice("stable-1");
        DeviceConnection originalConnection = configManager.getConnectionConfig("stable-1");
        List<DataPoint> originalPoints = configManager.getDataPoints("stable-1");
        DeviceContext originalContext = configManager.getDeviceContext("stable-1");

        when(configSyncService.loadAllDevices()).thenReturn(List.of(device("new-a"), device("new-b")));
        when(configSyncService.loadDataPoints("new-a")).thenReturn(List.of(point("new-a")));
        when(configSyncService.loadDataPoints("new-b")).thenThrow(new IllegalStateException("points down"));
        when(configSyncService.loadConnectionConfig("new-a")).thenReturn(connection("new-a"));

        ReflectionTestUtils.invokeMethod(configManager, "loadAllConfig");

        assertSame(originalDevice, configManager.getDevice("stable-1"));
        assertSame(originalConnection, configManager.getConnectionConfig("stable-1"));
        assertEquals(originalPoints, configManager.getDataPoints("stable-1"));
        assertSame(originalContext, configManager.getDeviceContext("stable-1"));
        assertEquals(originalVersion, configManager.getDeviceConfigVersion("stable-1"));
        assertFalse(configManager.containsDevice("new-a"));
        assertFalse(configManager.containsDevice("new-b"));
    }

    @Test
    void shouldPreserveLiveCachesWhenCandidateConnectionLoadFails() {
        assertTrue(configManager.updateDeviceConfig(device("stable-2")));
        assertTrue(configManager.updateConnectionConfig("stable-2", connection("stable-2")));
        assertTrue(configManager.updateDataPoints("stable-2", List.of(point("stable-2"))));
        long originalVersion = configManager.getDeviceConfigVersion("stable-2");
        DeviceContext originalContext = configManager.getDeviceContext("stable-2");

        when(configSyncService.loadAllDevices()).thenReturn(List.of(device("new-c")));
        when(configSyncService.loadDataPoints("new-c")).thenReturn(List.of(point("new-c")));
        when(configSyncService.loadConnectionConfig("new-c")).thenThrow(new IllegalStateException("connection down"));

        ReflectionTestUtils.invokeMethod(configManager, "loadAllConfig");

        assertSame(originalContext, configManager.getDeviceContext("stable-2"));
        assertEquals(originalVersion, configManager.getDeviceConfigVersion("stable-2"));
        assertFalse(configManager.containsDevice("new-c"));
    }

    @Test
    void shouldRefreshLocalTemporaryDeviceFromCacheWithoutRemoteAccess() {
        when(configSyncService.loadDevice("local-1")).thenReturn(null);
        configManager.saveLocalDeviceConfig(
                device("local-1"),
                connection("local-1"),
                List.of(point("local-1")),
                false);

        assertTrue(configManager.refreshDeviceConfig("local-1"));

        verify(configSyncService, never()).loadDevice("local-1");
        verify(configSyncService, never()).loadDataPoints("local-1");
        verify(configSyncService, never()).loadConnectionConfig("local-1");
        assertTrue(configManager.containsDevice("local-1"));
        assertTrue(configManager.isLocalTemporaryDevice("local-1"));
        assertNotNull(configManager.getConnectionConfig("local-1"));
        assertFalse(configManager.getDataPoints("local-1").isEmpty());
        assertNotNull(configManager.getDeviceContext("local-1"));
    }

    @Test
    void shouldPreserveExactLocalConfigDuringRefresh() {
        configManager.saveLocalDeviceConfig(
                device("local-exact"),
                connection("local-exact"),
                List.of(point("local-exact")),
                false);

        assertTrue(configManager.refreshDeviceConfig("local-exact"));

        DeviceConnection savedConnection = configManager.getConnectionConfig("local-exact");
        DataPoint savedPoint = configManager.getDataPoints("local-exact").get(0);
        DeviceContext savedContext = configManager.getDeviceContext("local-exact");
        assertEquals("127.0.0.1", savedConnection.getHost());
        assertEquals(502, savedConnection.getPort());
        assertEquals("temperature", savedPoint.getPointCode());
        assertEquals("40001", savedPoint.getAddress());
        assertNotNull(savedContext);
        assertNotNull(savedContext.getConnectionConfig());
        assertEquals("temperature", savedContext.getDataPoints().get(0).getPointCode());
    }

    @Test
    void shouldReplaceLocalPointsInCacheAndContextWhenOverwriting() {
        configManager.saveLocalDeviceConfig(
                device("local-replace"),
                connection("local-replace"),
                List.of(point("local-replace")),
                false);

        DataPoint replacement = point("local-replace");
        replacement.setPointCode("spindle_speed");
        replacement.setAddress("40004");
        assertTrue(configManager.saveLocalDeviceConfig(
                device("local-replace"),
                connection("local-replace"),
                List.of(replacement),
                true));

        assertEquals("spindle_speed", configManager.getDataPoints("local-replace").get(0).getPointCode());
        assertEquals("40004", configManager.getDataPoints("local-replace").get(0).getAddress());
        assertEquals("spindle_speed", configManager.getDeviceContext("local-replace").getDataPoints().get(0).getPointCode());
    }

    @Test
    void shouldLoadHistoricalRemotePointsWithoutRejectingValidSiblings() {
        ProtocolPointValidator validator = new ProtocolPointValidator() {
            @Override
            public boolean supports(DeviceInfo device) {
                return "OMRON_FINS".equals(device.getProtocolType());
            }

            @Override
            public void validate(List<DataPoint> points) {
                for (DataPoint point : points) {
                    if ("bad".equals(point.getAddress())) {
                        throw new IllegalArgumentException("无效的 FINS 地址");
                    }
                }
            }
        };
        @SuppressWarnings("unchecked")
        ObjectProvider<ProtocolPointValidator> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenReturn(Stream.of(validator));
        ConfigManager manager = new ConfigManager(configSyncService, eventPublisher,
                new FieldUniquenessValidator(), null, null, provider);
        DeviceInfo remote = device("fins-history");
        remote.setProtocolType("OMRON_FINS");
        DataPoint bad = point("fins-history");
        bad.setPointId("bad");
        bad.setAddress("bad");
        DataPoint good = point("fins-history");
        good.setPointId("good");
        good.setPointCode("good");
        good.setAddress("D100");
        when(configSyncService.loadDevice("fins-history")).thenReturn(remote);
        when(configSyncService.loadDataPoints("fins-history")).thenReturn(List.of(bad, good));

        assertTrue(manager.refreshDeviceConfig("fins-history"));
        assertEquals(2, manager.getDataPoints("fins-history").size());
        assertEquals("bad", manager.getDataPoints("fins-history").get(0).getAddress());
        assertEquals("D100", manager.getDataPoints("fins-history").get(1).getAddress());
    }

    @Test
    void shouldRefreshRemoteDeviceFromRemoteSource() {
        DeviceInfo remoteDevice = device("remote-refresh");
        DeviceConnection remoteConnection = connection("remote-refresh");
        DataPoint remotePoint = point("remote-refresh");
        when(configSyncService.loadDevice("remote-refresh")).thenReturn(remoteDevice);
        when(configSyncService.loadDataPoints("remote-refresh")).thenReturn(List.of(remotePoint));
        when(configSyncService.loadConnectionConfig("remote-refresh")).thenReturn(remoteConnection);

        assertTrue(configManager.refreshDeviceConfig("remote-refresh"));

        verify(configSyncService).loadDevice("remote-refresh");
        verify(configSyncService).loadDataPoints("remote-refresh");
        verify(configSyncService).loadConnectionConfig("remote-refresh");
        assertTrue(configManager.containsDevice("remote-refresh"));
        assertFalse(configManager.isLocalTemporaryDevice("remote-refresh"));
        assertEquals("127.0.0.1", configManager.getConnectionConfig("remote-refresh").getHost());
    }

    @Test
    void shouldNotResurrectConnectionCacheAfterRemoteDeviceDeletion() {
        assertTrue(configManager.updateDeviceConfig(device("deleted-remote")));
        assertTrue(configManager.updateConnectionConfig("deleted-remote", connection("deleted-remote")));
        assertTrue(configManager.updateDataPoints("deleted-remote", List.of(point("deleted-remote"))));
        when(configSyncService.loadDevice("deleted-remote")).thenReturn(null);
        when(configSyncService.loadConnectionConfig("deleted-remote")).thenReturn(connection("deleted-remote"));

        assertFalse(configManager.refreshDeviceConfig("deleted-remote"));
        assertFalse(configManager.containsDevice("deleted-remote"));
        assertNull(configManager.getConnectionConfig("deleted-remote"));
        assertNull(configManager.getDeviceContext("deleted-remote"));
        assertEquals(0L, configManager.getDeviceConfigVersion("deleted-remote"));
        verify(eventPublisher).publishEvent(argThat((Object event) -> event instanceof ConfigUpdateEvent change
                && "deleted-remote".equals(change.getDeviceId())
                && ConfigUpdateType.LOCAL_DELETE.getValue().equals(change.getConfigType())
                && change.getPreviousVersion() > 0 && change.getConfigVersion() > change.getPreviousVersion()));
        ReflectionTestUtils.invokeMethod(configManager, "handleConfigChange",
                ConfigUpdateEvent.createConnectionUpdateEvent("deleted-remote"));
        assertNull(configManager.getConnectionConfig("deleted-remote"));
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
    void shouldValidateBundleWithoutChangingCallerOrCachedObjects() {
        DeviceInfo existing = device("valid-target");
        assertTrue(configManager.updateDeviceConfig(existing));
        DeviceInfo candidate = device("other-device");
        DataPoint point = point("other-device");
        assertThrows(IllegalArgumentException.class,
                () -> configManager.validateDeviceContext("valid-target", candidate, null, List.of(point)));

        assertEquals("other-device", candidate.getDeviceId());
        assertEquals("other-device", point.getDeviceId());
        assertEquals("valid-target", configManager.getDevice("valid-target").getDeviceId());
    }

    @Test
    void shouldRejectForeignImportedPointAndBindOnlyMissingOwnership() {
        DeviceInfo imported = device("import-target");
        DataPoint sourcePoint = point("other-device");
        sourcePoint.setPointId("point-1");
        assertFalse(configManager.replaceDeviceContextsAtomically(List.of(
                DeviceContext.of(imported, connection("import-target"), List.of(sourcePoint)))));
        assertFalse(configManager.containsDevice("import-target"));
        sourcePoint.setDeviceId(null);
        assertTrue(configManager.replaceDeviceContextsAtomically(List.of(
                DeviceContext.of(imported, connection("import-target"), List.of(sourcePoint)))));

        assertEquals("import-target", configManager.getDataPoints("import-target").get(0).getDeviceId());
        assertNull(sourcePoint.getDeviceId());
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

    @Test
    void sameEffectiveLocalSaveShouldNotAdvanceVersionOrPublishEvent() {
        assertTrue(configManager.saveLocalDeviceConfig(device("same-local"), connection("same-local"),
                List.of(point("same-local")), false));
        long version = configManager.getDeviceConfigVersion("same-local");
        DeviceContext before = configManager.getDeviceContext("same-local");
        assertTrue(configManager.saveLocalDeviceConfig(device("same-local"), connection("same-local"),
                List.of(point("same-local")), true));
        assertEquals(version, configManager.getDeviceConfigVersion("same-local"));
        assertSame(before, configManager.getDeviceContext("same-local"));
        verify(eventPublisher, times(1)).publishEvent(org.mockito.ArgumentMatchers.any(ConfigUpdateEvent.class));
    }

    @Test
    void sameEffectiveBundleShouldCheckCasThenKeepVersion() {
        assertTrue(configManager.updateDeviceConfig(device("same-bundle")));
        long version = configManager.getDeviceConfigVersion("same-bundle");
        DeviceInfo candidate = device("same-bundle");
        candidate.setStatus("ONLINE");
        candidate.setUpdateTime(new java.util.Date());
        ConfigManager.DeviceConfigCommitResult result = configManager.replaceDeviceContextAtomically(
                "same-bundle", candidate, null, List.of(), version);
        assertEquals(version, result.configVersion());
        assertThrows(ConfigVersionConflictException.class, () -> configManager.replaceDeviceContextAtomically(
                "same-bundle", candidate, null, List.of(), version - 1));
        verify(eventPublisher, times(1)).publishEvent(org.mockito.ArgumentMatchers.any(ConfigUpdateEvent.class));
    }

    @Test
    void fullSourceCollisionShouldPreserveLocalOwnershipAndVersion() {
        assertTrue(configManager.saveLocalDeviceConfig(device("collision"), connection("collision"),
                List.of(point("collision")), false));
        long version = configManager.getDeviceConfigVersion("collision");
        DeviceInfo remote = device("collision");
        remote.setDeviceName("不能覆盖本地");
        when(configSyncService.loadAllDevices()).thenReturn(List.of(remote));
        ReflectionTestUtils.invokeMethod(configManager, "loadAllConfig");
        assertTrue(configManager.isLocalTemporaryDevice("collision"));
        assertEquals(version, configManager.getDeviceConfigVersion("collision"));
        assertEquals("test-device", configManager.getDevice("collision").getDeviceName());
    }

    @Test
    void bulkImportShouldOnlyAdvanceAndNotifyChangedDevice() {
        DeviceContext first = DeviceContext.of(device("bulk-A"), connection("bulk-A"), List.of(point("bulk-A")));
        DeviceContext second = DeviceContext.of(device("bulk-B"), connection("bulk-B"), List.of(point("bulk-B")));
        assertTrue(configManager.replaceDeviceContextsAtomically(List.of(first, second)));
        long firstVersion = configManager.getDeviceConfigVersion("bulk-A");
        long secondVersion = configManager.getDeviceConfigVersion("bulk-B");
        org.mockito.Mockito.clearInvocations(eventPublisher);
        DeviceInfo changed = device("bulk-B");
        changed.setDeviceName("新名称");
        assertTrue(configManager.replaceDeviceContextsAtomically(List.of(first,
                DeviceContext.of(changed, connection("bulk-B"), List.of(point("bulk-B"))))));
        assertEquals(firstVersion, configManager.getDeviceConfigVersion("bulk-A"));
        assertTrue(configManager.getDeviceConfigVersion("bulk-B") > secondVersion);
        verify(eventPublisher, times(1)).publishEvent(argThat((Object event) -> event instanceof ConfigUpdateEvent change
                && "bulk-B".equals(change.getDeviceId()) && change.getPreviousVersion() == secondVersion));
    }

    @Test
    void importShouldNotTransferExistingLocalSource() {
        assertTrue(configManager.saveLocalDeviceConfig(device("owned-local"), connection("owned-local"),
                List.of(point("owned-local")), false));
        long version = configManager.getDeviceConfigVersion("owned-local");
        assertFalse(configManager.replaceDeviceContextsAtomically(List.of(
                DeviceContext.of(device("owned-local"), connection("owned-local"), List.of(point("owned-local"))))));
        assertTrue(configManager.isLocalTemporaryDevice("owned-local"));
        assertEquals(version, configManager.getDeviceConfigVersion("owned-local"));
    }

    @Test
    void remoteConnectionChangeShouldPublishVersionedTargetEvent() {
        assertTrue(configManager.updateDeviceConfig(device("connection-event")));
        long version = configManager.getDeviceConfigVersion("connection-event");
        when(configSyncService.loadConnectionConfig("connection-event")).thenReturn(connection("connection-event"));
        ReflectionTestUtils.invokeMethod(configManager, "reloadConnectionConfig", "connection-event");
        verify(eventPublisher).publishEvent(argThat((Object event) -> event instanceof ConfigUpdateEvent change
                && "connection-event".equals(change.getDeviceId())
                && ConfigUpdateType.CONNECTION.getValue().equals(change.getConfigType())
                && change.getPreviousVersion() == version && change.getConfigVersion() > version));
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
        point.setPointId(deviceId + ":temperature");
        point.setPointCode("temperature");
        point.setAddress("40001");
        point.setDataType("FLOAT");
        return point;
    }
}
