package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.manager.CollectionManager;
import com.wangbin.collector.core.collector.protocol.base.ProtocolCollector;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateService;
import com.wangbin.collector.core.collector.statistics.CollectionStatistics;
import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.model.DeviceContext;
import com.wangbin.collector.core.config.model.ConfigUpdateEvent;
import com.wangbin.collector.core.port.CollectionHealthReporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.spy;

class DeviceLifecycleCoordinatorTest {

    private CollectionManager collectionManager;
    private ConfigManager configManager;
    private DeviceBatchPlanner deviceBatchPlanner;
    private SchedulerRuntimeState runtimeState;
    private CollectorProperties collectorProperties;
    private ThreadPoolExecutor deviceStartExecutor;
    private ExecutorService lifecycleCallExecutor;
    private CollectionTaskGuard collectionTaskGuard;
    private CollectionStatistics collectionStatistics;
    private CollectionHealthReporter healthTracker;
    private DeviceBatchExecutor deviceBatchExecutor;
    private ReconnectCoordinator reconnectCoordinator;
    private DeviceLifecycleCoordinator lifecycleCoordinator;
    private ProtocolBatchStrategy protocolBatchStrategy;

    @BeforeEach
    void setUp() {
        collectionManager = mock(CollectionManager.class);
        configManager = mock(ConfigManager.class);
        deviceBatchPlanner = mock(DeviceBatchPlanner.class);
        runtimeState = new SchedulerRuntimeState();
        runtimeState.initializeTimeSlices(1, 1000);
        collectorProperties = new CollectorProperties();
        collectorProperties.getAdaptiveCollection().setEnabled(false);
        collectorProperties.getScheduler().setDeviceStartTimeoutMs(1000);
        protocolBatchStrategy = mock(ProtocolBatchStrategy.class);
        when(protocolBatchStrategy.defaultBatchSize(anyString())).thenReturn(10);
        when(protocolBatchStrategy.maxBatchSize(anyString())).thenReturn(100);
        deviceStartExecutor = fixedPool("lifecycle-start", 2);
        lifecycleCallExecutor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            thread.setName("lifecycle-call-" + thread.getId());
            return thread;
        });
        collectionTaskGuard = new CollectionTaskGuard();
        collectionStatistics = mock(CollectionStatistics.class);
        healthTracker = mock(CollectionHealthReporter.class);
        deviceBatchExecutor = mock(DeviceBatchExecutor.class);
        reconnectCoordinator = mock(ReconnectCoordinator.class);
        lifecycleCoordinator = newLifecycleCoordinator(deviceStartExecutor);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        shutdownExecutor(deviceStartExecutor);
        shutdownExecutor(lifecycleCallExecutor);
    }

    @Test
    void changedRunningConfigurationWithoutScheduledRestartMustNotClaimPending() throws Exception {
        String deviceId = "save-no-restart-task";
        setupSingleDevice(deviceId);
        when(configManager.getDeviceConfigVersion(deviceId)).thenReturn(1L);
        assertTrue(lifecycleCoordinator.startDevice(deviceId));
        long revision = lifecycleCoordinator.getIntentRevision(deviceId);
        when(configManager.getDeviceConfigVersion(deviceId)).thenReturn(2L);

        assertEquals("FAILED", lifecycleCoordinator.startDeviceAfterConfigSave(deviceId, revision).status());
        assertEquals(revision, lifecycleCoordinator.getIntentRevision(deviceId));
    }

    @Test
    void failedRunningIntentWithoutPendingRestartMustAllowExplicitRetry() throws Exception {
        String deviceId = "retry-failed-start";
        setupSingleDevice(deviceId);
        when(configManager.getDeviceConfigVersion(deviceId)).thenReturn(1L);
        doThrow(new IllegalStateException("第一次注册失败")).doNothing()
                .when(collectionManager).registerDevice(any(DeviceInfo.class));
        assertFalse(lifecycleCoordinator.startDevice(deviceId));
        long failedRevision = lifecycleCoordinator.getIntentRevision(deviceId);
        assertTrue(lifecycleCoordinator.startDevice(deviceId));
        assertTrue(lifecycleCoordinator.isDeviceRunning(deviceId));
        assertTrue(lifecycleCoordinator.getIntentRevision(deviceId) > failedRevision);
    }

    @Test
    void configRestartMustStillCompleteAfterSaveStartHandsOffStartingIntent() throws Exception {
        String deviceId = "save-starting-restart";
        setupSingleDevice(deviceId);
        when(configManager.getDeviceConfigVersion(deviceId)).thenReturn(1L);
        CountDownLatch connectEntered = new CountDownLatch(1);
        CountDownLatch releaseOldConnect = new CountDownLatch(1);
        AtomicInteger connectCalls = new AtomicInteger();
        doAnswer(invocation -> {
            if (connectCalls.incrementAndGet() == 1) {
                connectEntered.countDown();
                awaitReleaseIgnoringInterrupt(releaseOldConnect);
            }
            return null;
        }).when(collectionManager).connectDevice(eq(deviceId), anyLong());
        AtomicReference<Runnable> restart = new AtomicReference<>();
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        when(scheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class))).thenAnswer(invocation -> {
            restart.set(invocation.getArgument(0));
            return future;
        });
        ConfigRestartCoordinator coordinator = new ConfigRestartCoordinator(lifecycleCoordinator,
                mock(TimeSliceConfigCoordinator.class), scheduler);
        CompletableFuture<Boolean> oldStart = startAsync(deviceId);
        try {
            assertTrue(connectEntered.await(1, TimeUnit.SECONDS));
            long revision = lifecycleCoordinator.getIntentRevision(deviceId);
            long oldGeneration = runtimeState.getStartingGeneration(deviceId);
            when(configManager.getDeviceConfigVersion(deviceId)).thenReturn(2L);
            coordinator.handleConfigUpdate(configEvent("points", deviceId));
            assertEquals("RESTART_PENDING", lifecycleCoordinator.startDeviceAfterConfigSave(deviceId, revision).status());
            assertEquals(revision, lifecycleCoordinator.getIntentRevision(deviceId));
            restart.get().run();
            assertTrue(lifecycleCoordinator.isDeviceRunning(deviceId));
            assertEquals(2L, runtimeState.getAppliedConfigVersion(deviceId));
            assertTrue(runtimeState.getScheduleInfo(deviceId).getGeneration() > oldGeneration);
            assertEquals(0, coordinator.pendingTaskCountForTest());
            assertEquals(revision, lifecycleCoordinator.getIntentRevision(deviceId));
        } finally {
            releaseOldConnect.countDown();
        }
        assertFalse(oldStart.get(1, TimeUnit.SECONDS));
    }

    @Test
    void lateDeleteAfterRecreationMustNotInvalidateOrStopNewGeneration() throws Exception {
        String deviceId = "late-delete-recreated";
        setupSingleDevice(deviceId);
        DeviceInfo local = device(deviceId, "MODBUS_TCP");
        local.setDeviceName("本地设备");
        local.setStatus("1");
        DataPoint localPoint = point(deviceId, "p1");
        localPoint.setAddress("40001");
        localPoint.setDataType("FLOAT");
        org.springframework.context.ApplicationEventPublisher publisher = mock(org.springframework.context.ApplicationEventPublisher.class);
        ConfigManager manager = new ConfigManager(
                mock(com.wangbin.collector.core.config.manager.ConfigSyncService.class), publisher, null, null);
        AtomicReference<ConfigUpdateEvent> deletion = new AtomicReference<>();
        AtomicReference<Runnable> release = new AtomicReference<>();
        doAnswer(invocation -> {
            ConfigUpdateEvent event = invocation.getArgument(0);
            if ("local-delete".equals(event.getConfigType())) {
                deletion.set(event);
                release.set(manager.retainDeletedConfigurationVersion(deviceId, event.getConfigVersion()));
            }
            return null;
        }).when(publisher).publishEvent(any(ConfigUpdateEvent.class));
        assertTrue(manager.saveLocalDeviceConfig(local, connection(deviceId, "MODBUS_TCP"), List.of(localPoint), false));
        assertTrue(manager.deleteLocalDeviceConfig(deviceId));
        long deletionVersion = deletion.get().getConfigVersion();
        long revision = lifecycleCoordinator.invalidateDeviceForDeletion(deviceId, deletionVersion, manager);
        assertTrue(revision >= 0L);
        Object deviceLock = lifecycleCoordinator.acquireLifecycleLockForTest(deviceId);
        CompletableFuture<Long> lateInvalidation = CompletableFuture.supplyAsync(() ->
                lifecycleCoordinator.invalidateDeviceForDeletion(deviceId, deletionVersion, manager), lifecycleCallExecutor);
        try {
            assertTrue(manager.saveLocalDeviceConfig(local, connection(deviceId, "MODBUS_TCP"), List.of(localPoint), false));
            when(configManager.getDeviceConfigVersion(deviceId)).thenReturn(manager.getDeviceConfigVersion(deviceId));
            assertTrue(lifecycleCoordinator.startDevice(deviceId));
        } finally {
            lifecycleCoordinator.releaseLifecycleLockForTest(deviceId, deviceLock);
        }
        long generation = runtimeState.lastGeneration(deviceId);
        long startRevision = lifecycleCoordinator.getIntentRevision(deviceId);
        assertEquals(-1L, lateInvalidation.get(1, TimeUnit.SECONDS));
        assertTrue(lifecycleCoordinator.stopDeletedDevice(deviceId, revision, true, true, deletionVersion, manager));
        assertEquals(startRevision, lifecycleCoordinator.getIntentRevision(deviceId));
        assertTrue(collectionTaskGuard.isCurrent(deviceId, generation));
        assertTrue(lifecycleCoordinator.isDeviceRunning(deviceId));
        verify(collectionManager, never()).cleanupDevice(deviceId);
        release.get().run();
        assertTrue(manager.getDeviceConfigVersion(deviceId) > deletionVersion);
    }

    @Test
    void saveAfterStartingInvalidationMustHandOffWithoutNewIntentOrGeneration() throws Exception {
        String deviceId = "save-starting-handoff";
        setupSingleDevice(deviceId);
        when(configManager.getDeviceConfigVersion(deviceId)).thenReturn(1L);
        runtimeState.requestRunning(deviceId);
        long revision = lifecycleCoordinator.getIntentRevision(deviceId);
        long generation = collectionTaskGuard.activateNextGeneration(deviceId);
        runtimeState.markStarting(deviceId);
        runtimeState.markStartingGeneration(deviceId, generation);
        runtimeState.recordAppliedConfigVersion(deviceId, generation, 1L);
        when(configManager.getDeviceConfigVersion(deviceId)).thenReturn(2L);
        ConfigRestartCoordinator coordinator = pendingConfigRestartCoordinator();
        coordinator.handleConfigUpdate(configEvent("points", deviceId));

        DeviceLifecycleCoordinator.StartAfterConfigSaveResult result =
                lifecycleCoordinator.startDeviceAfterConfigSave(deviceId, revision);

        assertEquals("RESTART_PENDING", result.status());
        assertTrue(result.accepted());
        assertEquals(revision, lifecycleCoordinator.getIntentRevision(deviceId));
        assertFalse(runtimeState.isStarting(deviceId));
        assertFalse(collectionTaskGuard.isCurrent(deviceId, generation));
        verify(collectionManager, never()).registerDevice(any(DeviceInfo.class));
    }

    @Test
    void userStopDuringSaveMustNotBeOverriddenBySaveStart() throws Exception {
        String deviceId = "save-stop-priority";
        setupSingleDevice(deviceId);
        runtimeState.requestRunning(deviceId);
        long saveRevision = lifecycleCoordinator.getIntentRevision(deviceId);
        assertTrue(lifecycleCoordinator.stopDevice(deviceId));
        long stoppedRevision = lifecycleCoordinator.getIntentRevision(deviceId);

        DeviceLifecycleCoordinator.StartAfterConfigSaveResult result =
                lifecycleCoordinator.startDeviceAfterConfigSave(deviceId, saveRevision);

        assertEquals("STOP_SUPERSEDED", result.status());
        assertFalse(result.accepted());
        assertEquals(stoppedRevision, lifecycleCoordinator.getIntentRevision(deviceId));
        assertEquals(SchedulerRuntimeState.DesiredState.STOPPED, lifecycleCoordinator.getDesiredState(deviceId));
        verify(collectionManager, never()).registerDevice(any(DeviceInfo.class));
    }

    @Test
    void repeatedStartDuringConfigurationRestartMustPreserveIntentRevision() throws Exception {
        String deviceId = "pending-config-start";
        setupSingleDevice(deviceId);
        runtimeState.requestRunning(deviceId);
        long revision = runtimeState.getIntentRevision(deviceId);
        when(configManager.getDeviceConfigVersion(deviceId)).thenReturn(2L);
        pendingConfigRestartCoordinator().handleConfigUpdate(configEvent("points", deviceId));
        assertTrue(lifecycleCoordinator.startDevice(deviceId));
        assertEquals(revision, runtimeState.getIntentRevision(deviceId));
        verify(collectionManager, never()).registerDevice(any(DeviceInfo.class));
    }

    @Test
    void stopWhileStartingMustNeverMarkRunning() throws Exception {
        String deviceId = "dev-stop-starting";
        setupSingleDevice(deviceId);
        CountDownLatch connectEntered = new CountDownLatch(1);
        CountDownLatch releaseConnect = new CountDownLatch(1);
        blockConnectUntil(deviceId, connectEntered, releaseConnect);

        CompletableFuture<Boolean> startFuture = startAsync(deviceId);
        assertTrue(connectEntered.await(1, TimeUnit.SECONDS));
        long oldGeneration = runtimeState.getStartingGeneration(deviceId);
        assertTrue(oldGeneration > 0L);
        assertTrue(runtimeState.isStarting(deviceId));

        CompletableFuture<Boolean> stopFuture = CompletableFuture.supplyAsync(
                () -> lifecycleCoordinator.stopDevice(deviceId),
                lifecycleCallExecutor);
        assertTrue(stopFuture.get(1, TimeUnit.SECONDS));
        releaseConnect.countDown();

        assertFalse(startFuture.get(1, TimeUnit.SECONDS));
        assertFalse(runtimeState.isRunning(deviceId));
        assertFalse(runtimeState.isStarting(deviceId));
        assertFalse(collectionTaskGuard.isCurrent(deviceId, oldGeneration));
        assertTrue(runtimeState.getSliceTasks(0).isEmpty());
        verify(collectionStatistics, never()).startCollection(eq(deviceId), anyInt());
        verify(healthTracker, never()).markDeviceStarted(deviceId);
        verify(collectionManager, never()).rebuildReadPlans(eq(deviceId), anyList());
    }

    @Test
    void invalidationDuringPrepareMustAbortBeforeGenerationActivation() throws Exception {
        String deviceId = "dev-invalidate-prepare";
        setupSingleDevice(deviceId);
        DataPoint point = point(deviceId, "p1");
        CountDownLatch pointsReadEntered = new CountDownLatch(1);
        CountDownLatch releasePointsRead = new CountDownLatch(1);
        doAnswer(invocation -> {
            pointsReadEntered.countDown();
            releasePointsRead.await(1, TimeUnit.SECONDS);
            return List.of(point);
        }).when(configManager).getDataPoints(deviceId);

        CompletableFuture<Boolean> startFuture = startAsync(deviceId);
        assertTrue(pointsReadEntered.await(1, TimeUnit.SECONDS));
        assertTrue(runtimeState.isStarting(deviceId));

        lifecycleCoordinator.invalidateDeviceForConfigChange(deviceId);
        releasePointsRead.countDown();

        assertFalse(startFuture.get(1, TimeUnit.SECONDS));
        assertFalse(runtimeState.isStarting(deviceId));
        assertFalse(runtimeState.isRunning(deviceId));
        assertEquals(0L, runtimeState.getStartingGeneration(deviceId));
        verify(collectionManager, never()).registerDevice(org.mockito.ArgumentMatchers.any(DeviceInfo.class));
        verify(collectionManager, never()).connectDevice(eq(deviceId), anyLong());
        verify(healthTracker, never()).markDeviceStarted(deviceId);
    }

    @Test
    void stopAfterStartingInvalidationShouldCleanupRegisteredCollector() throws Exception {
        String deviceId = "dev-starting-invalidation-cleanup";
        setupSingleDevice(deviceId);
        CountDownLatch connectEntered = new CountDownLatch(1);
        CountDownLatch releaseConnect = new CountDownLatch(1);
        blockConnectUntil(deviceId, connectEntered, releaseConnect);

        CompletableFuture<Boolean> startFuture = startAsync(deviceId);
        assertTrue(connectEntered.await(1, TimeUnit.SECONDS));
        verify(collectionManager).registerDevice(org.mockito.ArgumentMatchers.any(DeviceInfo.class));
        assertTrue(runtimeState.isStarting(deviceId));

        lifecycleCoordinator.invalidateDeviceForConfigChange(deviceId);
        assertFalse(runtimeState.isStarting(deviceId));

        assertTrue(lifecycleCoordinator.stopDeviceAfterConfigInvalidation(deviceId, false, true));
        releaseConnect.countDown();

        assertFalse(startFuture.get(1, TimeUnit.SECONDS));
        verify(collectionManager).cleanupDevice(deviceId);
        verify(collectionManager, never()).disconnectDevice(deviceId);
        assertFalse(runtimeState.isRunning(deviceId));
        assertFalse(runtimeState.isStarting(deviceId));
    }

    @Test
    void staleStartGenerationMustNotBecomeRunning() throws Exception {
        String deviceId = "dev-stale-start";
        setupSingleDevice(deviceId);
        CountDownLatch oldConnectEntered = new CountDownLatch(1);
        CountDownLatch releaseOldConnect = new CountDownLatch(1);
        AtomicInteger connectCalls = new AtomicInteger(0);
        doAnswer(invocation -> {
            if (connectCalls.incrementAndGet() == 1) {
                oldConnectEntered.countDown();
                awaitReleaseIgnoringInterrupt(releaseOldConnect);
            }
            return null;
        }).when(collectionManager).connectDevice(eq(deviceId), anyLong());

        CompletableFuture<Boolean> oldStartFuture = startAsync(deviceId);
        assertTrue(oldConnectEntered.await(1, TimeUnit.SECONDS));
        long oldGeneration = runtimeState.getStartingGeneration(deviceId);
        assertTrue(oldGeneration > 0L);

        assertTrue(lifecycleCoordinator.stopDevice(deviceId));
        CompletableFuture<Boolean> newStartFuture = startAsync(deviceId);
        assertTrue(newStartFuture.get(1, TimeUnit.SECONDS));
        DeviceScheduleInfo newScheduleInfo = runtimeState.getScheduleInfo(deviceId);
        assertNotNull(newScheduleInfo);
        long newGeneration = newScheduleInfo.getGeneration();
        assertNotEquals(oldGeneration, newGeneration);

        releaseOldConnect.countDown();
        assertFalse(oldStartFuture.get(1, TimeUnit.SECONDS));
        assertTrue(runtimeState.isRunning(deviceId));
        assertEquals(newGeneration, runtimeState.getScheduleInfo(deviceId).getGeneration());
        assertTrue(runtimeState.getSliceTasks(0).stream().allMatch(task -> task.generation == newGeneration));
        assertFalse(collectionTaskGuard.isCurrent(deviceId, oldGeneration));
        assertTrue(collectionTaskGuard.isCurrent(deviceId, newGeneration));
        verify(collectionStatistics, times(1)).startCollection(eq(deviceId), anyInt());
        verify(healthTracker, times(1)).markDeviceStarted(deviceId);
    }

    @Test
    void shutdownMustCancelStartingDevices() throws Exception {
        String runningDevice = "dev-running";
        String startingDevice1 = "dev-starting-b";
        String startingDevice2 = "dev-starting-c";
        setupSingleDevice(runningDevice);
        setupSingleDevice(startingDevice1);
        setupSingleDevice(startingDevice2);
        CountDownLatch startingEntered = new CountDownLatch(2);
        CountDownLatch releaseStarting = new CountDownLatch(1);
        doAnswer(invocation -> null).when(collectionManager).connectDevice(eq(runningDevice), anyLong());
        blockConnectUntilCancelledOrReleased(startingDevice1, startingEntered, releaseStarting);
        blockConnectUntilCancelledOrReleased(startingDevice2, startingEntered, releaseStarting);

        assertTrue(lifecycleCoordinator.startDevice(runningDevice));
        CompletableFuture<Boolean> startingFuture1 = startAsync(startingDevice1);
        CompletableFuture<Boolean> startingFuture2 = startAsync(startingDevice2);
        assertTrue(startingEntered.await(1, TimeUnit.SECONDS));
        long generation1 = runtimeState.getStartingGeneration(startingDevice1);
        long generation2 = runtimeState.getStartingGeneration(startingDevice2);
        assertTrue(generation1 > 0L);
        assertTrue(generation2 > 0L);

        lifecycleCoordinator.stopAllDevices();
        releaseStarting.countDown();

        assertFalse(startingFuture1.get(1, TimeUnit.SECONDS));
        assertFalse(startingFuture2.get(1, TimeUnit.SECONDS));
        assertTrue(runtimeState.getActiveDeviceIds().isEmpty());
        assertFalse(runtimeState.isRunning(runningDevice));
        assertFalse(runtimeState.isStarting(startingDevice1));
        assertFalse(runtimeState.isStarting(startingDevice2));
        assertFalse(collectionTaskGuard.isCurrent(startingDevice1, generation1));
        assertFalse(collectionTaskGuard.isCurrent(startingDevice2, generation2));
        verify(healthTracker, never()).markDeviceStarted(startingDevice1);
        verify(healthTracker, never()).markDeviceStarted(startingDevice2);
    }

    @Test
    void reservedStartShouldBeVisibleToStopAllDevices() throws Exception {
        String deviceId = "dev-reserved-start";
        setupSingleDevice(deviceId);

        runtimeState.requestRunning(deviceId);
        DeviceLifecycleCoordinator.StartReservation reservation = lifecycleCoordinator.reserveStartForConfigRestart(deviceId);

        assertNotNull(reservation);
        assertTrue(runtimeState.isStarting(deviceId));
        assertTrue(runtimeState.getActiveDeviceIds().contains(deviceId));
        assertTrue(collectionTaskGuard.isCurrent(deviceId, reservation.generation()));

        lifecycleCoordinator.stopAllDevices();

        assertFalse(runtimeState.isStarting(deviceId));
        assertFalse(runtimeState.isRunning(deviceId));
        assertFalse(collectionTaskGuard.isCurrent(deviceId, reservation.generation()));
        verify(collectionManager).cleanupDevice(deviceId);

        assertFalse(lifecycleCoordinator.continueReservedStart(reservation));
        verify(collectionManager, never()).registerDevice(org.mockito.ArgumentMatchers.any(DeviceInfo.class));
        verify(collectionManager, never()).connectDevice(eq(deviceId), anyLong());
        verify(collectionStatistics, never()).startCollection(eq(deviceId), anyInt());
        verify(healthTracker, never()).markDeviceStarted(deviceId);
    }

    @Test
    void reserveStartShouldReturnNullWhenStartingGenerationIsNotVisibleBeforeReturn() throws Exception {
        String deviceId = "dev-reserve-stale-before-return";
        runtimeState = spy(new SchedulerRuntimeState());
        runtimeState.initializeTimeSlices(1, 1000);
        lifecycleCoordinator = newLifecycleCoordinator(deviceStartExecutor);
        setupSingleDevice(deviceId);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            runtimeState.clearStarting(deviceId);
            return null;
        }).when(runtimeState).markStartingGeneration(eq(deviceId), anyLong());

        runtimeState.requestRunning(deviceId);
        DeviceLifecycleCoordinator.StartReservation reservation = lifecycleCoordinator.reserveStartForConfigRestart(deviceId);

        assertNull(reservation);
        assertFalse(runtimeState.isStarting(deviceId));
        assertFalse(collectionTaskGuard.isCurrent(deviceId, 1L));
        verify(collectionManager, never()).registerDevice(org.mockito.ArgumentMatchers.any(DeviceInfo.class));
        verify(collectionManager, never()).connectDevice(eq(deviceId), anyLong());
    }

    @Test
    void cancelAllAfterStartReservedButBeforeContinueShouldPreventRunning() throws Exception {
        String deviceId = "dev-config-reserved-shutdown";
        setupSingleDevice(deviceId);
        ScheduledExecutorService timeSliceScheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> restartFuture = mock(ScheduledFuture.class);
        TimeSliceConfigCoordinator timeSliceConfigCoordinator = mock(TimeSliceConfigCoordinator.class);
        AtomicReference<Runnable> restartCommand = new AtomicReference<>();
        CountDownLatch reservationCompleted = new CountDownLatch(1);
        CountDownLatch releaseContinuation = new CountDownLatch(1);
        AtomicReference<DeviceLifecycleCoordinator.StartReservation> reservedReservation = new AtomicReference<>();
        when(timeSliceScheduler.schedule(any(Runnable.class), eq(1000L), eq(TimeUnit.MILLISECONDS)))
                .thenAnswer(invocation -> {
                    restartCommand.set(invocation.getArgument(0));
                    return restartFuture;
                });
        ConfigRestartCoordinator restartCoordinator = new ConfigRestartCoordinator(
                lifecycleCoordinator,
                timeSliceConfigCoordinator,
                timeSliceScheduler) {
            @Override
            void beforeReservedStartContinuationForTest(String reservedDeviceId,
                                                        DeviceLifecycleCoordinator.StartReservation reservation) {
                reservedReservation.set(reservation);
                reservationCompleted.countDown();
                try {
                    releaseContinuation.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        assertTrue(lifecycleCoordinator.startDevice(deviceId));

        when(configManager.getDeviceConfigVersion(deviceId)).thenReturn(1L);
        restartCoordinator.handleConfigUpdate(configEvent("device", deviceId));
        Thread restartThread = new Thread(() -> restartCommand.get().run());
        restartThread.start();
        assertTrue(reservationCompleted.await(1, TimeUnit.SECONDS));
        DeviceLifecycleCoordinator.StartReservation reservation = reservedReservation.get();

        assertNotNull(reservation);
        assertTrue(runtimeState.isStarting(deviceId));
        assertTrue(runtimeState.getActiveDeviceIds().contains(deviceId));
        assertTrue(collectionTaskGuard.isCurrent(deviceId, reservation.generation()));

        restartCoordinator.cancelAll();
        lifecycleCoordinator.stopAllDevices();
        releaseContinuation.countDown();
        restartThread.join(1000L);

        assertFalse(runtimeState.isRunning(deviceId));
        assertFalse(runtimeState.isStarting(deviceId));
        assertFalse(collectionTaskGuard.isCurrent(deviceId, reservation.generation()));
        verify(timeSliceConfigCoordinator, never()).adjustTimeSlicesAfterWorkloadChange();
        verify(collectionManager, times(1)).registerDevice(org.mockito.ArgumentMatchers.any(DeviceInfo.class));
        verify(collectionManager, times(1)).connectDevice(eq(deviceId), anyLong());
    }

    @Test
    void blockedConnectMustNotBlockIndependentDeviceStart() throws Exception {
        String blockedDevice = "dev-connect-blocked";
        String healthyDevice = "dev-connect-healthy";
        setupSingleDevice(blockedDevice);
        setupSingleDevice(healthyDevice);
        CountDownLatch blockedEntered = new CountDownLatch(1);
        CountDownLatch releaseBlocked = new CountDownLatch(1);
        blockConnectUntil(blockedDevice, blockedEntered, releaseBlocked);
        doAnswer(invocation -> null).when(collectionManager).connectDevice(eq(healthyDevice), anyLong());

        CompletableFuture<Boolean> blockedStart = startAsync(blockedDevice);
        assertTrue(blockedEntered.await(1, TimeUnit.SECONDS));
        assertTrue(runtimeState.isStarting(blockedDevice));

        CompletableFuture<Boolean> healthyStart = startAsync(healthyDevice);

        assertTrue(healthyStart.get(1, TimeUnit.SECONDS));
        assertTrue(runtimeState.isRunning(healthyDevice));
        assertFalse(runtimeState.isRunning(blockedDevice));
        assertTrue(lifecycleCoordinator.stopDevice(blockedDevice));
        releaseBlocked.countDown();
        assertFalse(blockedStart.get(1, TimeUnit.SECONDS));
    }

    @Test
    void bacnetSubscriptionPointsAreAutoSubscribedAndExcludedFromPollingPlan() throws Exception {
        String deviceId = "dev-bacnet";
        DeviceInfo deviceInfo = device(deviceId, "BACNET_IP");
        DeviceConnection connection = connection(deviceId, "BACNET_IP");
        connection.setExtJson(Map.of("covEnabled", true));
        DataPoint subscriptionPoint = point(deviceId, "p1");
        subscriptionPoint.setCollectionMode("SUBSCRIPTION");
        DataPoint pollingPoint = point(deviceId, "p2");
        pollingPoint.setCollectionMode("POLLING");
        TestPointSelectionCollector bacnetCollector = new TestPointSelectionCollector(deviceInfo);

        when(configManager.getDevice(deviceId)).thenReturn(deviceInfo);
        when(configManager.getDataPoints(deviceId)).thenReturn(List.of(subscriptionPoint, pollingPoint));
        when(configManager.getDataPointsAndAdaptiveConfig(deviceId)).thenReturn(List.of(subscriptionPoint, pollingPoint));
        when(configManager.getConnectionConfig(deviceId)).thenReturn(connection);
        when(configManager.getDeviceContext(deviceId))
                .thenReturn(DeviceContext.of(deviceInfo, connection, List.of(subscriptionPoint, pollingPoint)));
        when(collectionManager.getCollector(deviceId)).thenReturn(bacnetCollector);
        doAnswer(invocation -> null).when(collectionManager).registerDevice(deviceInfo);
        doAnswer(invocation -> null).when(collectionManager).connectDevice(eq(deviceId), anyLong());
        doAnswer(invocation -> null).when(collectionManager).rebuildReadPlans(eq(deviceId), anyList());
        doAnswer(invocation -> null).when(collectionManager).subscribePoints(eq(deviceId), anyList());
        when(deviceBatchPlanner.plan(eq(deviceId), anyList(), eq(1), org.mockito.ArgumentMatchers.anyLong(), eq(1L)))
                .thenAnswer(invocation -> List.of(new DeviceBatchTask(
                        deviceId,
                        invocation.getArgument(1),
                        0,
                        invocation.getArgument(3),
                        invocation.getArgument(4))));

        boolean started = lifecycleCoordinator.startDevice(deviceId);

        assertTrue(started);
        verify(collectionManager).subscribePoints(eq(deviceId), eq(List.of(subscriptionPoint)));
        DeviceBatchTask task = runtimeState.getSliceTasks(0).get(0);
        assertEquals(1, task.points.size());
        assertEquals("p2", task.points.get(0).getPointId());
    }

    @Test
    void stopBetweenConnectSubmitAndFutureRegistrationMustCancelStart() throws Exception {
        String deviceId = "dev-submit-registration-race";
        SubmitRegistrationGateExecutor gatedExecutor = new SubmitRegistrationGateExecutor("submit-registration-gate");
        replaceDeviceStartExecutor(gatedExecutor);
        setupSingleDevice(deviceId);
        CountDownLatch connectEntered = new CountDownLatch(1);
        CountDownLatch releaseConnect = new CountDownLatch(1);
        AtomicBoolean connectInterrupted = new AtomicBoolean(false);
        doAnswer(invocation -> {
            connectEntered.countDown();
            try {
                releaseConnect.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                connectInterrupted.set(true);
                Thread.currentThread().interrupt();
            }
            return null;
        }).when(collectionManager).connectDevice(eq(deviceId), anyLong());

        CompletableFuture<Boolean> startFuture = startAsync(deviceId);
        assertTrue(gatedExecutor.awaitFirstSubmitEntered());
        assertTrue(connectEntered.await(1, TimeUnit.SECONDS));
        long generation = runtimeState.getStartingGeneration(deviceId);
        assertTrue(generation > 0L);

        CompletableFuture<Boolean> stopFuture = CompletableFuture.supplyAsync(
                () -> lifecycleCoordinator.stopDevice(deviceId),
                lifecycleCallExecutor);
        gatedExecutor.allowFirstSubmitReturn();

        assertTrue(stopFuture.get(1, TimeUnit.SECONDS));
        assertFalse(startFuture.get(1, TimeUnit.SECONDS));
        releaseConnect.countDown();
        waitUntil(() -> lifecycleCoordinator.startingFutureCountForTest() == 0);
        assertTrue(connectInterrupted.get());
        assertFalse(runtimeState.isStarting(deviceId));
        assertFalse(runtimeState.isRunning(deviceId));
        assertFalse(collectionTaskGuard.isCurrent(deviceId, generation));
    }

    @Test
    void staleGenerationMustNotModifyNewCollectorDuringStartCommit() throws Exception {
        String deviceId = "dev-stale-commit";
        GateFirstGetExecutor gatedExecutor = new GateFirstGetExecutor("post-connect-gate");
        replaceDeviceStartExecutor(gatedExecutor);
        setupBacnetDevice(deviceId);
        doAnswer(invocation -> null).when(collectionManager).connectDevice(eq(deviceId), anyLong());

        CompletableFuture<Boolean> oldStartFuture = startAsync(deviceId);
        assertTrue(gatedExecutor.awaitFirstGetBlocked());
        long oldGeneration = runtimeState.getStartingGeneration(deviceId);
        assertTrue(oldGeneration > 0L);

        assertTrue(lifecycleCoordinator.stopDevice(deviceId));
        CompletableFuture<Boolean> newStartFuture = startAsync(deviceId);
        assertTrue(newStartFuture.get(1, TimeUnit.SECONDS));
        DeviceScheduleInfo newScheduleInfo = runtimeState.getScheduleInfo(deviceId);
        assertNotNull(newScheduleInfo);
        long newGeneration = newScheduleInfo.getGeneration();

        gatedExecutor.allowFirstGetReturn();
        assertFalse(oldStartFuture.get(1, TimeUnit.SECONDS));
        assertNotEquals(oldGeneration, newGeneration);
        assertTrue(runtimeState.isRunning(deviceId));
        assertEquals(newGeneration, runtimeState.getScheduleInfo(deviceId).getGeneration());
        assertTrue(runtimeState.getSliceTasks(0).stream().allMatch(task -> task.generation == newGeneration));
        assertFalse(collectionTaskGuard.isCurrent(deviceId, oldGeneration));
        assertTrue(collectionTaskGuard.isCurrent(deviceId, newGeneration));
        verify(collectionManager, times(1)).rebuildReadPlans(eq(deviceId), anyList());
        verify(collectionManager, times(1)).subscribePoints(eq(deviceId), anyList());
        verify(collectionStatistics, times(1)).startCollection(eq(deviceId), anyInt());
        verify(healthTracker, times(1)).markDeviceStarted(deviceId);
    }

    @Test
    void lifecycleLockHolderMustNotSplitForSameDevice() throws Exception {
        String deviceId = "dev-lock-holder";
        Object firstHolder = lifecycleCoordinator.acquireLifecycleLockForTest(deviceId);
        boolean firstReleased = false;
        LifecycleLockProbe secondProbe = new LifecycleLockProbe(deviceId);
        LifecycleLockProbe thirdProbe = new LifecycleLockProbe(deviceId);

        CompletableFuture<Void> secondRun = secondProbe.start();
        waitUntil(() -> lifecycleCoordinator.lifecycleLockReferenceCountForTest(deviceId) == 2);
        assertSame(firstHolder, lifecycleCoordinator.lifecycleLockHolderForTest(deviceId));

        lifecycleCoordinator.releaseLifecycleLockForTest(deviceId, firstHolder);
        firstReleased = true;
        Object secondHolder = secondProbe.awaitAcquired();
        assertSame(firstHolder, secondHolder);

        CompletableFuture<Void> thirdRun = thirdProbe.start();
        waitUntil(() -> lifecycleCoordinator.lifecycleLockReferenceCountForTest(deviceId) == 2);
        assertSame(firstHolder, lifecycleCoordinator.lifecycleLockHolderForTest(deviceId));

        secondProbe.release();
        secondRun.get(1, TimeUnit.SECONDS);
        Object thirdHolder = thirdProbe.awaitAcquired();
        assertSame(firstHolder, thirdHolder);

        thirdProbe.release();
        thirdRun.get(1, TimeUnit.SECONDS);
        waitUntil(() -> lifecycleCoordinator.lifecycleLockHolderCountForTest() == 0);

        if (!firstReleased) {
            lifecycleCoordinator.releaseLifecycleLockForTest(deviceId, firstHolder);
        }
    }

    @Test
    void rejectedStartExecutorShouldCleanupStartingState() throws Exception {
        String deviceId = "dev-start-rejected";
        ThreadPoolExecutor rejectingExecutor = fixedPool("reject-start", 1);
        rejectingExecutor.shutdownNow();
        replaceDeviceStartExecutor(rejectingExecutor);
        setupSingleDevice(deviceId);

        boolean started = lifecycleCoordinator.startDevice(deviceId);

        assertFalse(started);
        assertFalse(runtimeState.isStarting(deviceId));
        assertFalse(runtimeState.isRunning(deviceId));
        assertEquals(0, lifecycleCoordinator.startingFutureCountForTest());
        assertTrue(runtimeState.getSliceTasks(0).isEmpty());
        verify(collectionManager).cleanupDeviceIfGeneration(eq(deviceId), anyLong());
        verify(collectionStatistics, never()).startCollection(eq(deviceId), anyInt());
        verify(healthTracker, never()).markDeviceStarted(deviceId);
    }

    @Test
    void startTimeoutShouldCleanupStartingStateAndRuntimeResources() throws Exception {
        String deviceId = "dev-start-timeout";
        setupSingleDevice(deviceId);
        CountDownLatch connectEntered = new CountDownLatch(1);
        CountDownLatch releaseConnect = new CountDownLatch(1);
        doAnswer(invocation -> {
            connectEntered.countDown();
            try {
                releaseConnect.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }).when(collectionManager).connectDevice(eq(deviceId), anyLong());

        boolean started = lifecycleCoordinator.startDevice(deviceId);
        releaseConnect.countDown();

        assertTrue(connectEntered.getCount() == 0);
        assertFalse(started);
        assertFalse(runtimeState.isStarting(deviceId));
        assertFalse(runtimeState.isRunning(deviceId));
        assertEquals(0, lifecycleCoordinator.startingFutureCountForTest());
        assertTrue(runtimeState.getSliceTasks(0).isEmpty());
        verify(collectionManager).cleanupDeviceIfGeneration(eq(deviceId), anyLong());
        verify(collectionStatistics, never()).startCollection(eq(deviceId), anyInt());
        verify(healthTracker, never()).markDeviceStarted(deviceId);
    }

    @Test
    void failedStartCleanupShouldBeIdempotent() throws Exception {
        String deviceId = "dev-cleanup-idempotent";
        setupSingleDevice(deviceId);
        assertTrue(runtimeState.markStarting(deviceId));
        long generation = collectionTaskGuard.activateNextGeneration(deviceId);
        runtimeState.markStartingGeneration(deviceId, generation);

        lifecycleCoordinator.cleanupFailedStart(deviceId, generation);
        lifecycleCoordinator.cleanupFailedStart(deviceId, generation);

        assertFalse(runtimeState.isStarting(deviceId));
        assertFalse(runtimeState.isRunning(deviceId));
        assertFalse(collectionTaskGuard.isCurrent(deviceId, generation));
        assertEquals(0, lifecycleCoordinator.startingFutureCountForTest());
        assertTrue(runtimeState.getSliceTasks(0).isEmpty());
    }

    @Test
    void nonCriticalCleanupFailureShouldStillAllowStopSuccess() throws Exception {
        String deviceId = "dev-non-critical-cleanup";
        setupSingleDevice(deviceId);
        assertTrue(lifecycleCoordinator.startDevice(deviceId));
        doThrow(new IllegalStateException("statistics cleanup failed"))
                .when(collectionStatistics).stopCollection(deviceId);

        assertTrue(lifecycleCoordinator.stopDevice(deviceId));

        assertFalse(runtimeState.isRunning(deviceId));
        assertFalse(runtimeState.isStarting(deviceId));
        verify(collectionManager).cleanupDevice(deviceId);
        verify(healthTracker).markDeviceStopped(deviceId);
    }

    @Test
    void criticalCleanupFailureShouldMakeStopReturnFalse() throws Exception {
        String deviceId = "dev-critical-cleanup";
        setupSingleDevice(deviceId);
        assertTrue(lifecycleCoordinator.startDevice(deviceId));
        doThrow(new IllegalStateException("cancel in-flight failed"))
                .when(deviceBatchExecutor).cancelDeviceInFlightTasks(deviceId);

        assertFalse(lifecycleCoordinator.stopDevice(deviceId));

        assertFalse(runtimeState.isRunning(deviceId));
        assertFalse(runtimeState.isStarting(deviceId));
        verify(collectionManager).cleanupDevice(deviceId);
        verify(collectionStatistics).stopCollection(deviceId);
        verify(healthTracker).markDeviceStopped(deviceId);
    }

    @Test
    void cleanupFailureMustNotSkipRemainingCleanupSteps() throws Exception {
        String deviceId = "dev-cleanup-continues";
        setupSingleDevice(deviceId);
        assertTrue(lifecycleCoordinator.startDevice(deviceId));
        doThrow(new IllegalStateException("remove runtime tasks failed"))
                .when(deviceBatchExecutor).cancelDeviceInFlightTasks(deviceId);

        assertFalse(lifecycleCoordinator.stopDevice(deviceId));

        verify(reconnectCoordinator, times(2)).clear(deviceId);
        verify(collectionStatistics).stopCollection(deviceId);
        verify(healthTracker).markDeviceStopped(deviceId);
        verify(collectionManager).cleanupDevice(deviceId);
    }

    @Test
    void collectorCleanupFailureShouldReturnFalseWithoutRetainingRunningState() throws Exception {
        String deviceId = "dev-disconnect-fallback";
        setupSingleDevice(deviceId);
        assertTrue(lifecycleCoordinator.startDevice(deviceId));
        doThrow(new IllegalStateException("cleanup failed"))
                .when(collectionManager).cleanupDevice(deviceId);

        assertFalse(lifecycleCoordinator.stopDevice(deviceId));

        verify(collectionManager).cleanupDevice(deviceId);
        assertFalse(runtimeState.isRunning(deviceId));
        assertFalse(runtimeState.isStarting(deviceId));
    }

    @Test
    void stopDeviceShouldRemainIdempotent() throws Exception {
        String deviceId = "dev-stop-idempotent";
        setupSingleDevice(deviceId);
        assertTrue(lifecycleCoordinator.startDevice(deviceId));

        assertTrue(lifecycleCoordinator.stopDevice(deviceId));
        assertTrue(lifecycleCoordinator.stopDevice(deviceId));

        assertFalse(runtimeState.isRunning(deviceId));
        assertFalse(runtimeState.isStarting(deviceId));
    }

    @Test
    void repeatedStartAndStopMustPreserveGenerationAndOtherDeviceIntent() throws Exception {
        setupSingleDevice("dev-intent-a");
        setupSingleDevice("dev-intent-b");
        assertEquals(SchedulerRuntimeState.DesiredState.STOPPED, runtimeState.getDesiredState("dev-intent-b"));
        assertTrue(lifecycleCoordinator.startDevice("dev-intent-a"));
        long generation = runtimeState.lastGeneration("dev-intent-a");
        long revision = lifecycleCoordinator.getIntentRevision("dev-intent-a");
        assertTrue(lifecycleCoordinator.startDevice("dev-intent-a"));
        assertEquals(generation, runtimeState.lastGeneration("dev-intent-a"));
        assertEquals(revision, lifecycleCoordinator.getIntentRevision("dev-intent-a"));
        assertTrue(lifecycleCoordinator.stopDevice("dev-intent-a"));
        assertTrue(lifecycleCoordinator.stopDevice("dev-intent-a"));
        assertEquals(SchedulerRuntimeState.DesiredState.STOPPED, runtimeState.getDesiredState("dev-intent-a"));
        assertEquals(generation, runtimeState.lastGeneration("dev-intent-a"));
        assertFalse(runtimeState.isRunning("dev-intent-b"));
        verify(collectionManager, never()).connectDevice(eq("dev-intent-b"), anyLong());
    }

    @Test
    void oldConfigRestartMustNotStopNewUserStartAfterStopStart() throws Exception {
        String deviceId = "dev-intent-revision";
        setupSingleDevice(deviceId);
        assertTrue(lifecycleCoordinator.startDevice(deviceId));
        long oldRevision = lifecycleCoordinator.getIntentRevision(deviceId);
        assertTrue(lifecycleCoordinator.stopDevice(deviceId));
        assertTrue(lifecycleCoordinator.startDevice(deviceId));
        long generation = runtimeState.lastGeneration(deviceId);
        assertNull(lifecycleCoordinator.reserveStartForConfigRestart(deviceId, oldRevision, true, false));
        assertTrue(runtimeState.isRunning(deviceId));
        assertEquals(generation, runtimeState.lastGeneration(deviceId));
        assertTrue(runtimeState.getSliceTasks(0).stream().allMatch(task -> task.generation == generation));
    }

    @Test
    void configRestartMustPreserveRunningIntentButStopMustInvalidateReservation() throws Exception {
        String deviceId = "dev-config-intent";
        setupSingleDevice(deviceId);
        assertTrue(lifecycleCoordinator.startDevice(deviceId));
        long revision = lifecycleCoordinator.getIntentRevision(deviceId);
        DeviceLifecycleCoordinator.StartReservation reservation = lifecycleCoordinator.reserveStartForConfigRestart(
                deviceId, revision, true, false);
        assertNotNull(reservation);
        assertEquals(revision, lifecycleCoordinator.getIntentRevision(deviceId));
        assertEquals(SchedulerRuntimeState.DesiredState.RUNNING, runtimeState.getDesiredState(deviceId));
        assertTrue(lifecycleCoordinator.stopDevice(deviceId));
        assertFalse(lifecycleCoordinator.continueReservedStart(reservation));
        assertEquals(SchedulerRuntimeState.DesiredState.STOPPED, runtimeState.getDesiredState(deviceId));
        assertFalse(runtimeState.isRunning(deviceId));
    }

    @Test
    void registerFailureMustFailStartWithoutSubmittingConnect() throws Exception {
        String deviceId = "dev-registration-failure";
        setupSingleDevice(deviceId);
        doThrow(new IllegalStateException("注册失败")).when(collectionManager).registerDevice(any(DeviceInfo.class));
        assertFalse(lifecycleCoordinator.startDevice(deviceId));
        assertFalse(runtimeState.isRunning(deviceId));
        assertFalse(runtimeState.isStarting(deviceId));
        verify(collectionManager, never()).connectDevice(eq(deviceId), anyLong());
    }

    @Test
    void retiredCollectorMustFinishOwnedConnectBeforeReplacementCanUseDeviceConnection() throws Exception {
        com.wangbin.collector.core.collector.factory.CollectorFactory factory = mock(
                com.wangbin.collector.core.collector.factory.CollectorFactory.class);
        com.wangbin.collector.core.connection.manager.ConnectionManager connections = mock(
                com.wangbin.collector.core.connection.manager.ConnectionManager.class);
        CollectionManager manager = new CollectionManager(factory, connections);
        DeviceInfo device = device("dev-owned-connect", "MODBUS_TCP");
        ProtocolCollector oldCollector = mock(ProtocolCollector.class);
        ProtocolCollector newCollector = mock(ProtocolCollector.class);
        when(factory.createCollector(device)).thenReturn(oldCollector, newCollector);
        manager.registerDevice(device);
        manager.bindRuntimeGeneration(device.getDeviceId(), 1L);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            awaitReleaseIgnoringInterrupt(release);
            return null;
        }).when(oldCollector).connect();
        Future<?> connecting = lifecycleCallExecutor.submit(() -> manager.connectDevice(device.getDeviceId(), 1L));
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        manager.cleanupDeviceIfGeneration(device.getDeviceId(), 1L);
        assertNull(manager.getCollector(device.getDeviceId()));
        org.junit.jupiter.api.Assertions.assertThrows(com.wangbin.collector.common.exception.CollectorException.class,
                () -> manager.registerDevice(device));
        release.countDown();
        connecting.get(1, TimeUnit.SECONDS);
        manager.registerDevice(device);
        manager.bindRuntimeGeneration(device.getDeviceId(), 2L);
        manager.cleanupDeviceIfGeneration(device.getDeviceId(), 1L);
        assertSame(newCollector, manager.getCollector(device.getDeviceId()));
        verify(oldCollector).destroy();
        verify(newCollector, never()).destroy();
        verify(newCollector, never()).disconnect();
        verify(connections).removeConnection(device.getDeviceId());
    }

    @Test
    void blockedCollectorCreationMustNotHoldAllDeviceRegistrationLock() throws Exception {
        com.wangbin.collector.core.collector.factory.CollectorFactory factory = mock(
                com.wangbin.collector.core.collector.factory.CollectorFactory.class);
        CollectionManager manager = new CollectionManager(factory, null);
        DeviceInfo deviceA = device("dev-owned-a", "MODBUS_TCP");
        DeviceInfo deviceB = device("dev-owned-b", "MODBUS_TCP");
        ProtocolCollector collectorA = mock(ProtocolCollector.class);
        ProtocolCollector collectorB = mock(ProtocolCollector.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(factory.createCollector(deviceA)).thenAnswer(invocation -> {
            entered.countDown();
            awaitReleaseIgnoringInterrupt(release);
            return collectorA;
        });
        when(factory.createCollector(deviceB)).thenReturn(collectorB);
        Future<?> registering = lifecycleCallExecutor.submit(() -> manager.registerDevice(deviceA));
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        Future<?> registeringB = lifecycleCallExecutor.submit(() -> manager.registerDevice(deviceB));
        registeringB.get(1, TimeUnit.SECONDS);
        assertSame(collectorB, manager.getCollector(deviceB.getDeviceId()));
        release.countDown();
        registering.get(1, TimeUnit.SECONDS);
    }

    @Test
    void userStopMustCancelPendingConfigRestartAndRejectForcedLateRunnable() throws Exception {
        String deviceId = "dev-stop-pending-config";
        setupSingleDevice(deviceId);
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> restartFuture = mock(ScheduledFuture.class);
        AtomicReference<Runnable> command = new AtomicReference<>();
        when(executor.schedule(any(Runnable.class), eq(1000L), eq(TimeUnit.MILLISECONDS))).thenAnswer(invocation -> {
            command.set(invocation.getArgument(0));
            return restartFuture;
        });
        ConfigRestartCoordinator coordinator = new ConfigRestartCoordinator(lifecycleCoordinator,
                mock(TimeSliceConfigCoordinator.class), executor);
        assertTrue(lifecycleCoordinator.startDevice(deviceId));
        when(configManager.getDeviceConfigVersion(deviceId)).thenReturn(1L);
        coordinator.handleConfigUpdate(configEvent("device", deviceId));
        assertEquals(1, coordinator.pendingTaskCountForTest());
        assertTrue(lifecycleCoordinator.stopDevice(deviceId));
        command.get().run();
        assertEquals(0, coordinator.pendingTaskCountForTest());
        assertEquals(SchedulerRuntimeState.DesiredState.STOPPED, runtimeState.getDesiredState(deviceId));
        assertFalse(runtimeState.isRunning(deviceId));
        verify(restartFuture).cancel(false);
        verify(collectionManager).registerDevice(any(DeviceInfo.class));
        verify(collectionManager).connectDevice(eq(deviceId), anyLong());
    }

    @Test
    void reloadDiffMustExcludeUnchangedAndUserStoppedDevices() throws Exception {
        setupSingleDevice("dev-reload-a");
        setupSingleDevice("dev-reload-b");
        when(configManager.getDeviceConfigVersion("dev-reload-a")).thenReturn(1L);
        when(configManager.getDeviceConfigVersion("dev-reload-b")).thenReturn(1L);
        assertTrue(lifecycleCoordinator.startDevice("dev-reload-a"));
        assertTrue(lifecycleCoordinator.startDevice("dev-reload-b"));
        assertTrue(lifecycleCoordinator.stopDevice("dev-reload-b"));
        assertTrue(lifecycleCoordinator.getChangedConfigDeviceIds().isEmpty());
        when(configManager.getDeviceConfigVersion("dev-reload-a")).thenReturn(2L);
        when(configManager.getDeviceConfigVersion("dev-reload-b")).thenReturn(2L);
        assertEquals(List.of("dev-reload-a"), lifecycleCoordinator.getChangedConfigDeviceIds());
        assertEquals(SchedulerRuntimeState.DesiredState.STOPPED, runtimeState.getDesiredState("dev-reload-b"));
    }

    @Test
    void oldReadContextMustNotInvokeNewGenerationCollector() throws Exception {
        com.wangbin.collector.core.collector.factory.CollectorFactory factory = mock(
                com.wangbin.collector.core.collector.factory.CollectorFactory.class);
        CollectionManager manager = new CollectionManager(factory, null);
        manager.setCollectionTaskGuard(collectionTaskGuard);
        String deviceId = "dev-owned-read";
        DeviceInfo device = device(deviceId, "MODBUS_TCP");
        ProtocolCollector oldCollector = mock(ProtocolCollector.class);
        ProtocolCollector newCollector = mock(ProtocolCollector.class,
                org.mockito.Mockito.withSettings().extraInterfaces(com.wangbin.collector.core.collector.protocol.base.ReadableCollector.class));
        when(factory.createCollector(device)).thenReturn(oldCollector, newCollector);
        long oldGeneration = collectionTaskGuard.activateNextGeneration(deviceId);
        manager.registerDevice(device);
        manager.bindRuntimeGeneration(deviceId, oldGeneration);
        manager.cleanupDeviceIfGeneration(deviceId, oldGeneration);
        collectionTaskGuard.clearDevice(deviceId);
        long generation = collectionTaskGuard.activateNextGeneration(deviceId);
        manager.registerDevice(device);
        manager.bindRuntimeGeneration(deviceId, generation);
        org.junit.jupiter.api.Assertions.assertThrows(com.wangbin.collector.common.exception.CollectorException.class,
                () -> collectionTaskGuard.callWithContext(deviceId, oldGeneration,
                        () -> manager.readPoints(deviceId, List.of(point(deviceId, "p1")))));
        verify((com.wangbin.collector.core.collector.protocol.base.ReadableCollector) newCollector, never()).readPoints(anyList());
        assertSame(newCollector, manager.getCollector(deviceId));
        verify(newCollector, never()).destroy();
        org.junit.jupiter.api.Assertions.assertThrows(com.wangbin.collector.common.exception.CollectorException.class,
                () -> manager.bindRuntimeGeneration(deviceId, oldGeneration));
    }

    @Test
    void deletionMustInvalidateIntentAndRemoveRuntimeIdentityWithoutLosingGenerationTombstone() throws Exception {
        String deviceId = "dev-delete-intent";
        setupSingleDevice(deviceId);
        assertTrue(lifecycleCoordinator.startDevice(deviceId));
        long generation = runtimeState.lastGeneration(deviceId);
        long oldRevision = lifecycleCoordinator.getIntentRevision(deviceId);
        long deletionRevision = lifecycleCoordinator.invalidateDeviceForDeletion(deviceId);
        assertTrue(lifecycleCoordinator.stopDeletedDevice(deviceId, deletionRevision, true, false));
        assertFalse(runtimeState.getKnownDeviceIds().contains(deviceId));
        assertEquals(SchedulerRuntimeState.DesiredState.STOPPED, runtimeState.getDesiredState(deviceId));
        assertEquals(generation, runtimeState.lastGeneration(deviceId));
        assertNull(lifecycleCoordinator.reserveStartForConfigRestart(deviceId, oldRevision, true, false));
        verify(collectionManager).cleanupDevice(deviceId);
    }

    @Test
    void lateConfigEventWithAlreadyAppliedVersionMustNotRestartUserStartedDevice() throws Exception {
        String deviceId = "dev-config-already-applied";
        setupSingleDevice(deviceId);
        when(configManager.getDeviceConfigVersion(deviceId)).thenReturn(1L);
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        ConfigRestartCoordinator coordinator = new ConfigRestartCoordinator(lifecycleCoordinator,
                mock(TimeSliceConfigCoordinator.class), executor);
        assertTrue(lifecycleCoordinator.startDevice(deviceId));
        long generation = runtimeState.lastGeneration(deviceId);
        coordinator.handleConfigUpdate(configEvent("device", deviceId));
        verify(executor, never()).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        assertEquals(0, coordinator.pendingTaskCountForTest());
        assertEquals(generation, runtimeState.lastGeneration(deviceId));
        assertTrue(collectionTaskGuard.isCurrent(deviceId, generation));
        assertTrue(runtimeState.isRunning(deviceId));
    }

    private DeviceLifecycleCoordinator newLifecycleCoordinator(ThreadPoolExecutor startExecutor) {
        PointRuntimeStateService pointRuntimeStateService = new PointRuntimeStateService();
        DeviceStartPreparer startPreparer = new DeviceStartPreparer(
                configManager,
                collectorProperties,
                collectionTaskGuard,
                pointRuntimeStateService,
                runtimeState,
                reconnectCoordinator);
        DeviceLifecycleCleanup lifecycleCleanup = new DeviceLifecycleCleanup(
                collectionManager,
                collectionStatistics,
                healthTracker,
                pointRuntimeStateService,
                runtimeState,
                deviceBatchExecutor,
                reconnectCoordinator,
                collectionTaskGuard);
        return new DeviceLifecycleCoordinator(
                collectionManager,
                collectionStatistics,
                healthTracker,
                deviceBatchPlanner,
                protocolBatchStrategy,
                collectionTaskGuard,
                runtimeState,
                new PerformanceMonitor(),
                startPreparer,
                lifecycleCleanup,
                startExecutor);
    }

    private void replaceDeviceStartExecutor(ThreadPoolExecutor startExecutor) throws InterruptedException {
        shutdownExecutor(deviceStartExecutor);
        deviceStartExecutor = startExecutor;
        lifecycleCoordinator = newLifecycleCoordinator(deviceStartExecutor);
    }

    private void setupSingleDevice(String deviceId) throws Exception {
        DeviceInfo deviceInfo = device(deviceId, "MODBUS_TCP");
        DeviceConnection connection = connection(deviceId, "MODBUS_TCP");
        DataPoint point = point(deviceId, "p1");
        when(configManager.getDevice(deviceId)).thenReturn(deviceInfo);
        when(configManager.getDataPoints(deviceId)).thenReturn(List.of(point));
        when(configManager.getDataPointsAndAdaptiveConfig(deviceId)).thenReturn(List.of(point));
        when(configManager.getConnectionConfig(deviceId)).thenReturn(connection);
        when(configManager.getDeviceContext(deviceId))
                .thenReturn(DeviceContext.of(deviceInfo, connection, List.of(point)));
        doAnswer(invocation -> null).when(collectionManager).registerDevice(deviceInfo);
        doAnswer(invocation -> null).when(collectionManager).cleanupDevice(anyString());
        doAnswer(invocation -> null).when(collectionManager).rebuildReadPlans(eq(deviceId), anyList());
        doAnswer(invocation -> null).when(collectionManager).disconnectDevice(deviceId);
        when(deviceBatchPlanner.plan(eq(deviceId), anyList(), eq(1), org.mockito.ArgumentMatchers.anyLong(), eq(1L)))
                .thenAnswer(invocation -> List.of(new DeviceBatchTask(
                        deviceId,
                        invocation.getArgument(1),
                        0,
                        invocation.getArgument(3),
                        invocation.getArgument(4))));
    }

    private void setupBacnetDevice(String deviceId) throws Exception {
        DeviceInfo deviceInfo = device(deviceId, "BACNET_IP");
        DeviceConnection connection = connection(deviceId, "BACNET_IP");
        connection.setExtJson(Map.of("covEnabled", true));
        connection.setConnectTimeout(3000);
        connection.setTimeout(3000);
        DataPoint subscriptionPoint = point(deviceId, "p1");
        subscriptionPoint.setCollectionMode("SUBSCRIPTION");
        DataPoint pollingPoint = point(deviceId, "p2");
        pollingPoint.setCollectionMode("POLLING");
        TestPointSelectionCollector bacnetCollector = new TestPointSelectionCollector(deviceInfo);

        when(configManager.getDevice(deviceId)).thenReturn(deviceInfo);
        when(configManager.getDataPoints(deviceId)).thenReturn(List.of(subscriptionPoint, pollingPoint));
        when(configManager.getDataPointsAndAdaptiveConfig(deviceId)).thenReturn(List.of(subscriptionPoint, pollingPoint));
        when(configManager.getConnectionConfig(deviceId)).thenReturn(connection);
        when(configManager.getDeviceContext(deviceId))
                .thenReturn(DeviceContext.of(deviceInfo, connection, List.of(subscriptionPoint, pollingPoint)));
        when(collectionManager.getCollector(deviceId)).thenReturn(bacnetCollector);
        doAnswer(invocation -> null).when(collectionManager).registerDevice(deviceInfo);
        doAnswer(invocation -> null).when(collectionManager).cleanupDevice(anyString());
        doAnswer(invocation -> null).when(collectionManager).disconnectDevice(deviceId);
        doAnswer(invocation -> null).when(collectionManager).rebuildReadPlans(eq(deviceId), anyList());
        doAnswer(invocation -> null).when(collectionManager).subscribePoints(eq(deviceId), anyList());
        when(deviceBatchPlanner.plan(eq(deviceId), anyList(), eq(1), org.mockito.ArgumentMatchers.anyLong(), eq(1L)))
                .thenAnswer(invocation -> List.of(new DeviceBatchTask(
                        deviceId,
                        invocation.getArgument(1),
                        0,
                        invocation.getArgument(3),
                        invocation.getArgument(4))));
    }

    private DeviceInfo device(String deviceId, String protocol) {
        DeviceInfo deviceInfo = new DeviceInfo();
        deviceInfo.setDeviceId(deviceId);
        deviceInfo.setProtocolType(protocol);
        deviceInfo.setConnectionType(protocol);
        return deviceInfo;
    }

    private DeviceConnection connection(String deviceId, String protocol) {
        DeviceConnection connection = new DeviceConnection();
        connection.setDeviceId(deviceId);
        connection.setConnectionType(protocol);
        connection.setHost("127.0.0.1");
        connection.setPort(502);
        connection.setConnectTimeout(50);
        connection.setReadTimeout(50);
        connection.setTimeout(50);
        return connection;
    }

    private DataPoint point(String deviceId, String pointId) {
        DataPoint point = new DataPoint();
        point.setDeviceId(deviceId);
        point.setPointId(pointId);
        point.setPointCode(pointId);
        point.setStatus(1);
        return point;
    }

    private ConfigRestartCoordinator pendingConfigRestartCoordinator() {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        when(scheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenAnswer(invocation -> future);
        return new ConfigRestartCoordinator(lifecycleCoordinator, mock(TimeSliceConfigCoordinator.class), scheduler);
    }

    private ConfigUpdateEvent configEvent(String configType, String deviceId) {
        ConfigUpdateEvent event = new ConfigUpdateEvent();
        event.setConfigType(configType);
        event.setDeviceId(deviceId);
        return event;
    }

    private CompletableFuture<Boolean> startAsync(String deviceId) {
        return CompletableFuture.supplyAsync(() -> lifecycleCoordinator.startDevice(deviceId), lifecycleCallExecutor);
    }

    private void blockConnectUntil(String deviceId, CountDownLatch entered, CountDownLatch release) throws Exception {
        doAnswer(invocation -> {
            entered.countDown();
            awaitReleaseIgnoringInterrupt(release);
            return null;
        }).when(collectionManager).connectDevice(eq(deviceId), anyLong());
    }

    private void blockConnectUntilCancelledOrReleased(String deviceId,
                                                     CountDownLatch entered,
                                                     CountDownLatch release) throws Exception {
        doAnswer(invocation -> {
            entered.countDown();
            try {
                release.await(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }).when(collectionManager).connectDevice(eq(deviceId), anyLong());
    }

    private void awaitReleaseIgnoringInterrupt(CountDownLatch release) {
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (release.getCount() > 0 && System.nanoTime() < deadline) {
            try {
                long remainingNanos = Math.max(1L, deadline - System.nanoTime());
                if (release.await(remainingNanos, TimeUnit.NANOSECONDS)) {
                    break;
                }
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void waitUntil(Condition condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.isSatisfied()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertTrue(condition.isSatisfied());
    }

    private ThreadPoolExecutor fixedPool(String namePrefix, int threads) {
        return new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(16),
                runnable -> {
                    Thread thread = new Thread(runnable);
                    thread.setDaemon(true);
                    thread.setName(namePrefix + "-" + thread.getId());
                    return thread;
                });
    }

    private void shutdownExecutor(ExecutorService executorService) throws InterruptedException {
        executorService.shutdownNow();
        assertTrue(executorService.awaitTermination(1, TimeUnit.SECONDS));
    }

    private final class LifecycleLockProbe {
        private final String deviceId;
        private final CountDownLatch release = new CountDownLatch(1);
        private final CompletableFuture<Object> acquired = new CompletableFuture<>();

        private LifecycleLockProbe(String deviceId) {
            this.deviceId = deviceId;
        }

        private CompletableFuture<Void> start() {
            return CompletableFuture.runAsync(() -> {
                Object lifecycleLock = lifecycleCoordinator.acquireLifecycleLockForTest(deviceId);
                acquired.complete(lifecycleLock);
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    lifecycleCoordinator.releaseLifecycleLockForTest(deviceId, lifecycleLock);
                }
            }, lifecycleCallExecutor);
        }

        private Object awaitAcquired() throws Exception {
            return acquired.get(1, TimeUnit.SECONDS);
        }

        private void release() {
            release.countDown();
        }
    }

    private static final class TestPointSelectionCollector implements ProtocolCollector, ProtocolPointSelectionSupport {
        private final DeviceInfo deviceInfo;

        private TestPointSelectionCollector(DeviceInfo deviceInfo) {
            this.deviceInfo = deviceInfo;
        }

        @Override
        public void init(DeviceInfo deviceInfo) {
        }

        @Override
        public void connect() {
        }

        @Override
        public void disconnect() {
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public String getConnectionStatus() {
            return "CONNECTED";
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
        public Map<String, Object> getDeviceStatus() {
            return Map.of("deviceId", deviceInfo.getDeviceId());
        }

        @Override
        public String getCollectorType() {
            return "TEST";
        }

        @Override
        public String getProtocolType() {
            return "BACNET_IP";
        }

        @Override
        public List<DataPoint> filterPollingPoints(List<DataPoint> points) {
            return points.stream()
                    .filter(point -> !"SUBSCRIPTION".equalsIgnoreCase(point.getCollectionMode()))
                    .toList();
        }

        @Override
        public List<DataPoint> filterAutoSubscriptionPoints(List<DataPoint> points) {
            return points.stream()
                    .filter(point -> "SUBSCRIPTION".equalsIgnoreCase(point.getCollectionMode()))
                    .toList();
        }
    }

    private static final class SubmitRegistrationGateExecutor extends ThreadPoolExecutor {
        private final CountDownLatch firstSubmitEntered = new CountDownLatch(1);
        private final CountDownLatch allowFirstSubmitReturn = new CountDownLatch(1);
        private final AtomicInteger submitCalls = new AtomicInteger(0);

        private SubmitRegistrationGateExecutor(String namePrefix) {
            super(
                    1,
                    1,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(16),
                    runnable -> {
                        Thread thread = new Thread(runnable);
                        thread.setDaemon(true);
                        thread.setName(namePrefix + "-" + thread.getId());
                        return thread;
                    });
        }

        @Override
        public Future<?> submit(Runnable task) {
            Future<?> future = super.submit(task);
            if (submitCalls.incrementAndGet() == 1) {
                firstSubmitEntered.countDown();
                awaitSubmitRelease();
            }
            return future;
        }

        private boolean awaitFirstSubmitEntered() throws InterruptedException {
            return firstSubmitEntered.await(1, TimeUnit.SECONDS);
        }

        private void allowFirstSubmitReturn() {
            allowFirstSubmitReturn.countDown();
        }

        private void awaitSubmitRelease() {
            try {
                if (!allowFirstSubmitReturn.await(2, TimeUnit.SECONDS)) {
                    throw new RejectedExecutionException("submit gate timeout");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RejectedExecutionException("submit gate interrupted", e);
            }
        }
    }

    private static final class GateFirstGetExecutor extends ThreadPoolExecutor {
        private final CountDownLatch firstGetBlocked = new CountDownLatch(1);
        private final CountDownLatch allowFirstGetReturn = new CountDownLatch(1);
        private final AtomicInteger submitCalls = new AtomicInteger(0);

        private GateFirstGetExecutor(String namePrefix) {
            super(
                    2,
                    2,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(16),
                    runnable -> {
                        Thread thread = new Thread(runnable);
                        thread.setDaemon(true);
                        thread.setName(namePrefix + "-" + thread.getId());
                        return thread;
                    });
        }

        @Override
        public Future<?> submit(Runnable task) {
            Future<?> future = super.submit(task);
            if (submitCalls.incrementAndGet() == 1) {
                return new GatedFuture(future, firstGetBlocked, allowFirstGetReturn);
            }
            return future;
        }

        private boolean awaitFirstGetBlocked() throws InterruptedException {
            return firstGetBlocked.await(1, TimeUnit.SECONDS);
        }

        private void allowFirstGetReturn() {
            allowFirstGetReturn.countDown();
        }
    }

    private static final class GatedFuture implements Future<Object> {
        private final Future<?> delegate;
        private final CountDownLatch getBlocked;
        private final CountDownLatch allowReturn;

        private GatedFuture(Future<?> delegate, CountDownLatch getBlocked, CountDownLatch allowReturn) {
            this.delegate = delegate;
            this.getBlocked = getBlocked;
            this.allowReturn = allowReturn;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return delegate.cancel(mayInterruptIfRunning);
        }

        @Override
        public boolean isCancelled() {
            return delegate.isCancelled();
        }

        @Override
        public boolean isDone() {
            return delegate.isDone();
        }

        @Override
        public Object get() throws InterruptedException, ExecutionException {
            Object result = delegate.get();
            getBlocked.countDown();
            allowReturn.await();
            return result;
        }

        @Override
        public Object get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            Object result = delegate.get(timeout, unit);
            getBlocked.countDown();
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0 || !allowReturn.await(remainingNanos, TimeUnit.NANOSECONDS)) {
                throw new TimeoutException("gated future timeout");
            }
            return result;
        }
    }

    @FunctionalInterface
    private interface Condition {
        boolean isSatisfied();
    }
}
