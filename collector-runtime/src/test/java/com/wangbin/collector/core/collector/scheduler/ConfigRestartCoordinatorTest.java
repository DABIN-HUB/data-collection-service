package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.core.config.model.ConfigUpdateEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfigRestartCoordinatorTest {

    @Test
    void repeatedConfigUpdateShouldOnlyKeepLatestRestartTask() {
        DeviceLifecycleCoordinator lifecycleCoordinator = mock(DeviceLifecycleCoordinator.class);
        TimeSliceConfigCoordinator timeSliceConfigCoordinator = mock(TimeSliceConfigCoordinator.class);
        CapturingScheduledExecutor scheduledExecutor = new CapturingScheduledExecutor();
        when(lifecycleCoordinator.isDeviceRunning("dev-config")).thenReturn(true);
        when(lifecycleCoordinator.stopDevice("dev-config")).thenReturn(true);
        when(lifecycleCoordinator.startDevice("dev-config")).thenReturn(true);
        ConfigRestartCoordinator coordinator = new ConfigRestartCoordinator(
                lifecycleCoordinator,
                timeSliceConfigCoordinator,
                scheduledExecutor);

        coordinator.handleConfigUpdate(event("device", "dev-config"));
        coordinator.handleConfigUpdate(event("points", "dev-config"));
        assertEquals(2, scheduledExecutor.tasks.size());
        assertTrue(scheduledExecutor.tasks.get(0).isCancelled());

        scheduledExecutor.tasks.get(1).runIfNotCancelled();

        verify(lifecycleCoordinator).stopDevice("dev-config");
        verify(lifecycleCoordinator).startDevice("dev-config");
        verify(timeSliceConfigCoordinator).adjustTimeSlicesAfterWorkloadChange();
        assertEquals(0, coordinator.pendingTaskCountForTest());
    }

    @Test
    void concurrentConfigUpdateShouldOnlyAllowLatestEffectiveRestartTask() throws Exception {
        DeviceLifecycleCoordinator lifecycleCoordinator = mock(DeviceLifecycleCoordinator.class);
        TimeSliceConfigCoordinator timeSliceConfigCoordinator = mock(TimeSliceConfigCoordinator.class);
        BlockingFirstScheduleExecutor scheduledExecutor = new BlockingFirstScheduleExecutor();
        when(lifecycleCoordinator.isDeviceRunning("dev-concurrent")).thenReturn(true);
        when(lifecycleCoordinator.stopDevice("dev-concurrent")).thenReturn(true);
        when(lifecycleCoordinator.startDevice("dev-concurrent")).thenReturn(true);
        ConfigRestartCoordinator coordinator = new ConfigRestartCoordinator(
                lifecycleCoordinator,
                timeSliceConfigCoordinator,
                scheduledExecutor);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread first = new Thread(() -> runCapturingFailure(
                () -> coordinator.handleConfigUpdate(event("device", "dev-concurrent")), failure));
        Thread second = new Thread(() -> runCapturingFailure(
                () -> coordinator.handleConfigUpdate(event("points", "dev-concurrent")), failure));

        first.start();
        assertTrue(scheduledExecutor.awaitFirstScheduleEntered());
        second.start();
        scheduledExecutor.releaseFirstSchedule();
        first.join(1000L);
        second.join(1000L);

        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        scheduledExecutor.tasks.forEach(CapturedFuture::runIfNotCancelled);
        verify(lifecycleCoordinator).stopDevice("dev-concurrent");
        verify(lifecycleCoordinator).startDevice("dev-concurrent");
        verify(timeSliceConfigCoordinator).adjustTimeSlicesAfterWorkloadChange();
        assertEquals(0, coordinator.pendingTaskCountForTest());
    }

    @Test
    void oldRunningRestartTaskMustNotRemoveNewPendingTask() throws Exception {
        DeviceLifecycleCoordinator lifecycleCoordinator = mock(DeviceLifecycleCoordinator.class);
        TimeSliceConfigCoordinator timeSliceConfigCoordinator = mock(TimeSliceConfigCoordinator.class);
        CapturingScheduledExecutor scheduledExecutor = new CapturingScheduledExecutor();
        CountDownLatch oldTaskEntered = new CountDownLatch(1);
        CountDownLatch releaseOldTask = new CountDownLatch(1);
        when(lifecycleCoordinator.isDeviceRunning("dev-overlap")).thenReturn(true);
        doAnswer(invocation -> {
            oldTaskEntered.countDown();
            releaseOldTask.await(1, TimeUnit.SECONDS);
            return true;
        }).when(lifecycleCoordinator).stopDevice("dev-overlap");
        when(lifecycleCoordinator.startDevice("dev-overlap")).thenReturn(true);
        ConfigRestartCoordinator coordinator = new ConfigRestartCoordinator(
                lifecycleCoordinator,
                timeSliceConfigCoordinator,
                scheduledExecutor);

        coordinator.handleConfigUpdate(event("device", "dev-overlap"));
        Thread oldRunner = new Thread(() -> scheduledExecutor.tasks.get(0).runEvenIfCancelled());
        oldRunner.start();
        assertTrue(oldTaskEntered.await(1, TimeUnit.SECONDS));
        coordinator.handleConfigUpdate(event("points", "dev-overlap"));
        releaseOldTask.countDown();
        oldRunner.join(1000L);

        assertEquals(1, coordinator.pendingTaskCountForTest());
        assertEquals(2, scheduledExecutor.tasks.size());
        assertTrue(!scheduledExecutor.tasks.get(1).isCancelled());
    }

    @Test
    void localDeleteShouldCancelPendingRestartAndScheduleStopForRunningDevice() {
        DeviceLifecycleCoordinator lifecycleCoordinator = mock(DeviceLifecycleCoordinator.class);
        TimeSliceConfigCoordinator timeSliceConfigCoordinator = mock(TimeSliceConfigCoordinator.class);
        CapturingScheduledExecutor scheduledExecutor = new CapturingScheduledExecutor();
        when(lifecycleCoordinator.isDeviceRunning("dev-delete")).thenReturn(true);
        ConfigRestartCoordinator coordinator = new ConfigRestartCoordinator(
                lifecycleCoordinator,
                timeSliceConfigCoordinator,
                scheduledExecutor);

        coordinator.handleConfigUpdate(event("device", "dev-delete"));
        coordinator.handleConfigUpdate(event("local-delete", "dev-delete"));

        verify(lifecycleCoordinator).invalidateDeviceForConfigChange("dev-delete");
        verify(lifecycleCoordinator, never()).stopDevice("dev-delete");
        assertEquals(2, scheduledExecutor.tasks.size());
        assertTrue(scheduledExecutor.tasks.get(0).isCancelled());
        assertTrue(!scheduledExecutor.tasks.get(1).isCancelled());
        assertEquals(0, coordinator.pendingTaskCountForTest());
        assertEquals(1, coordinator.pendingStopTaskCountForTest());

        scheduledExecutor.tasks.get(1).runIfNotCancelled();

        verify(lifecycleCoordinator).stopDeviceAfterConfigInvalidation("dev-delete", true, false);
        verify(lifecycleCoordinator, never()).startDevice("dev-delete");
        assertEquals(0, coordinator.pendingTaskCountForTest());
        assertEquals(0, coordinator.pendingStopTaskCountForTest());
    }

    @Test
    void configUpdateWhileStartingShouldInvalidateAndDebounceRestartWithoutSynchronousStop() {
        DeviceLifecycleCoordinator lifecycleCoordinator = mock(DeviceLifecycleCoordinator.class);
        TimeSliceConfigCoordinator timeSliceConfigCoordinator = mock(TimeSliceConfigCoordinator.class);
        CapturingScheduledExecutor scheduledExecutor = new CapturingScheduledExecutor();
        when(lifecycleCoordinator.isDeviceRunning("dev-starting-update")).thenReturn(false);
        when(lifecycleCoordinator.isDeviceStarting("dev-starting-update")).thenReturn(true);
        when(lifecycleCoordinator.stopDeviceAfterConfigInvalidation("dev-starting-update", false, true))
                .thenReturn(true);
        when(lifecycleCoordinator.startDevice("dev-starting-update")).thenReturn(true);
        ConfigRestartCoordinator coordinator = new ConfigRestartCoordinator(
                lifecycleCoordinator,
                timeSliceConfigCoordinator,
                scheduledExecutor);

        coordinator.handleConfigUpdate(event("device", "dev-starting-update"));

        verify(lifecycleCoordinator).invalidateDeviceForConfigChange("dev-starting-update");
        verify(lifecycleCoordinator, never()).startDevice("dev-starting-update");
        verify(lifecycleCoordinator, never()).stopDevice("dev-starting-update");
        assertEquals(1, scheduledExecutor.tasks.size());

        scheduledExecutor.tasks.get(0).runIfNotCancelled();

        verify(lifecycleCoordinator).stopDeviceAfterConfigInvalidation("dev-starting-update", false, true);
        verify(lifecycleCoordinator).startDevice("dev-starting-update");
        verify(timeSliceConfigCoordinator).adjustTimeSlicesAfterWorkloadChange();
        assertEquals(0, coordinator.pendingTaskCountForTest());
    }

    @Test
    void localDeleteShouldInvalidateStartingDeviceAndScheduleStopWithoutRestart() {
        DeviceLifecycleCoordinator lifecycleCoordinator = mock(DeviceLifecycleCoordinator.class);
        TimeSliceConfigCoordinator timeSliceConfigCoordinator = mock(TimeSliceConfigCoordinator.class);
        CapturingScheduledExecutor scheduledExecutor = new CapturingScheduledExecutor();
        when(lifecycleCoordinator.isDeviceRunning("dev-starting-delete")).thenReturn(false);
        when(lifecycleCoordinator.isDeviceStarting("dev-starting-delete")).thenReturn(true);
        ConfigRestartCoordinator coordinator = new ConfigRestartCoordinator(
                lifecycleCoordinator,
                timeSliceConfigCoordinator,
                scheduledExecutor);

        coordinator.handleConfigUpdate(event("local-delete", "dev-starting-delete"));

        verify(lifecycleCoordinator).invalidateDeviceForConfigChange("dev-starting-delete");
        verify(lifecycleCoordinator, never()).stopDevice("dev-starting-delete");
        verify(lifecycleCoordinator, never()).startDevice("dev-starting-delete");
        assertEquals(1, scheduledExecutor.tasks.size());
        assertEquals(0, coordinator.pendingTaskCountForTest());
        assertEquals(1, coordinator.pendingStopTaskCountForTest());

        scheduledExecutor.tasks.get(0).runIfNotCancelled();

        verify(lifecycleCoordinator).stopDeviceAfterConfigInvalidation("dev-starting-delete", false, true);
        verify(lifecycleCoordinator, never()).startDevice("dev-starting-delete");
        assertEquals(0, coordinator.pendingStopTaskCountForTest());
    }

    @Test
    void localDeletePendingStopShouldBeCancelledByCancelAll() {
        DeviceLifecycleCoordinator lifecycleCoordinator = mock(DeviceLifecycleCoordinator.class);
        TimeSliceConfigCoordinator timeSliceConfigCoordinator = mock(TimeSliceConfigCoordinator.class);
        CapturingScheduledExecutor scheduledExecutor = new CapturingScheduledExecutor();
        when(lifecycleCoordinator.isDeviceRunning("dev-delete-cancel")).thenReturn(true);
        ConfigRestartCoordinator coordinator = new ConfigRestartCoordinator(
                lifecycleCoordinator,
                timeSliceConfigCoordinator,
                scheduledExecutor);

        coordinator.handleConfigUpdate(event("local-delete", "dev-delete-cancel"));
        coordinator.cancelAll();

        assertEquals(1, scheduledExecutor.tasks.size());
        assertTrue(scheduledExecutor.tasks.get(0).isCancelled());
        assertEquals(0, coordinator.pendingStopTaskCountForTest());
        verify(lifecycleCoordinator, never()).startDevice("dev-delete-cancel");
    }

    @Test
    void concurrentLocalDeleteScheduleAndCancelAllShouldNotLeaveActiveFuture() throws Exception {
        DeviceLifecycleCoordinator lifecycleCoordinator = mock(DeviceLifecycleCoordinator.class);
        TimeSliceConfigCoordinator timeSliceConfigCoordinator = mock(TimeSliceConfigCoordinator.class);
        BlockingFirstScheduleExecutor scheduledExecutor = new BlockingFirstScheduleExecutor();
        when(lifecycleCoordinator.isDeviceRunning("dev-delete-race")).thenReturn(true);
        ConfigRestartCoordinator coordinator = new ConfigRestartCoordinator(
                lifecycleCoordinator,
                timeSliceConfigCoordinator,
                scheduledExecutor);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread scheduler = new Thread(() -> runCapturingFailure(
                () -> coordinator.handleConfigUpdate(event("local-delete", "dev-delete-race")), failure));
        Thread closer = new Thread(() -> runCapturingFailure(coordinator::cancelAll, failure));

        scheduler.start();
        assertTrue(scheduledExecutor.awaitFirstScheduleEntered());
        closer.start();
        scheduledExecutor.releaseFirstSchedule();
        scheduler.join(1000L);
        closer.join(1000L);

        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        assertEquals(1, scheduledExecutor.tasks.size());
        assertTrue(scheduledExecutor.tasks.get(0).isCancelled());
        assertEquals(0, coordinator.pendingStopTaskCountForTest());
    }

    @Test
    void repeatedLocalDeleteShouldNotAccumulateStopTasks() {
        DeviceLifecycleCoordinator lifecycleCoordinator = mock(DeviceLifecycleCoordinator.class);
        TimeSliceConfigCoordinator timeSliceConfigCoordinator = mock(TimeSliceConfigCoordinator.class);
        CapturingScheduledExecutor scheduledExecutor = new CapturingScheduledExecutor();
        when(lifecycleCoordinator.isDeviceRunning("dev-delete-repeat")).thenReturn(true);
        ConfigRestartCoordinator coordinator = new ConfigRestartCoordinator(
                lifecycleCoordinator,
                timeSliceConfigCoordinator,
                scheduledExecutor);

        coordinator.handleConfigUpdate(event("local-delete", "dev-delete-repeat"));
        coordinator.handleConfigUpdate(event("local-delete", "dev-delete-repeat"));
        coordinator.handleConfigUpdate(event("local-delete", "dev-delete-repeat"));

        assertEquals(3, scheduledExecutor.tasks.size());
        assertEquals(1, scheduledExecutor.tasks.stream().filter(task -> !task.isCancelled()).count());
        assertEquals(1, coordinator.pendingStopTaskCountForTest());
    }

    @Test
    void cancelAllShouldCancelPendingRestartTasks() {
        DeviceLifecycleCoordinator lifecycleCoordinator = mock(DeviceLifecycleCoordinator.class);
        TimeSliceConfigCoordinator timeSliceConfigCoordinator = mock(TimeSliceConfigCoordinator.class);
        CapturingScheduledExecutor scheduledExecutor = new CapturingScheduledExecutor();
        when(lifecycleCoordinator.isDeviceRunning("dev-cancel")).thenReturn(true);
        ConfigRestartCoordinator coordinator = new ConfigRestartCoordinator(
                lifecycleCoordinator,
                timeSliceConfigCoordinator,
                scheduledExecutor);

        coordinator.handleConfigUpdate(event("connection", "dev-cancel"));
        coordinator.cancelAll();

        assertEquals(1, scheduledExecutor.tasks.size());
        assertTrue(scheduledExecutor.tasks.get(0).isCancelled());
        assertEquals(0, coordinator.pendingTaskCountForTest());
    }

    @Test
    void cancelAllShouldPreventNewRestartScheduling() {
        DeviceLifecycleCoordinator lifecycleCoordinator = mock(DeviceLifecycleCoordinator.class);
        TimeSliceConfigCoordinator timeSliceConfigCoordinator = mock(TimeSliceConfigCoordinator.class);
        CapturingScheduledExecutor scheduledExecutor = new CapturingScheduledExecutor();
        when(lifecycleCoordinator.isDeviceRunning("dev-closed")).thenReturn(true);
        ConfigRestartCoordinator coordinator = new ConfigRestartCoordinator(
                lifecycleCoordinator,
                timeSliceConfigCoordinator,
                scheduledExecutor);

        coordinator.cancelAll();
        coordinator.handleConfigUpdate(event("device", "dev-closed"));

        assertEquals(0, scheduledExecutor.tasks.size());
        assertEquals(0, coordinator.pendingTaskCountForTest());
    }

    @Test
    void concurrentCancelAllAndScheduleShouldNotLeaveActiveFuture() throws Exception {
        DeviceLifecycleCoordinator lifecycleCoordinator = mock(DeviceLifecycleCoordinator.class);
        TimeSliceConfigCoordinator timeSliceConfigCoordinator = mock(TimeSliceConfigCoordinator.class);
        BlockingFirstScheduleExecutor scheduledExecutor = new BlockingFirstScheduleExecutor();
        when(lifecycleCoordinator.isDeviceRunning("dev-close-race")).thenReturn(true);
        ConfigRestartCoordinator coordinator = new ConfigRestartCoordinator(
                lifecycleCoordinator,
                timeSliceConfigCoordinator,
                scheduledExecutor);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread scheduler = new Thread(() -> runCapturingFailure(
                () -> coordinator.handleConfigUpdate(event("device", "dev-close-race")), failure));
        Thread closer = new Thread(() -> runCapturingFailure(coordinator::cancelAll, failure));

        scheduler.start();
        assertTrue(scheduledExecutor.awaitFirstScheduleEntered());
        closer.start();
        scheduledExecutor.releaseFirstSchedule();
        scheduler.join(1000L);
        closer.join(1000L);

        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        assertEquals(1, scheduledExecutor.tasks.size());
        assertTrue(scheduledExecutor.tasks.get(0).isCancelled());
        assertEquals(0, coordinator.pendingTaskCountForTest());
    }

    @Test
    void runningRestartTaskShouldNotStartAfterCancelAll() throws Exception {
        DeviceLifecycleCoordinator lifecycleCoordinator = mock(DeviceLifecycleCoordinator.class);
        TimeSliceConfigCoordinator timeSliceConfigCoordinator = mock(TimeSliceConfigCoordinator.class);
        CapturingScheduledExecutor scheduledExecutor = new CapturingScheduledExecutor();
        CountDownLatch stopEntered = new CountDownLatch(1);
        CountDownLatch releaseStop = new CountDownLatch(1);
        when(lifecycleCoordinator.isDeviceRunning("dev-running-close")).thenReturn(true);
        doAnswer(invocation -> {
            stopEntered.countDown();
            releaseStop.await(1, TimeUnit.SECONDS);
            return true;
        }).when(lifecycleCoordinator).stopDevice("dev-running-close");
        when(lifecycleCoordinator.startDevice("dev-running-close")).thenReturn(true);
        ConfigRestartCoordinator coordinator = new ConfigRestartCoordinator(
                lifecycleCoordinator,
                timeSliceConfigCoordinator,
                scheduledExecutor);

        coordinator.handleConfigUpdate(event("device", "dev-running-close"));
        Thread runner = new Thread(() -> scheduledExecutor.tasks.get(0).runIfNotCancelled());
        runner.start();
        assertTrue(stopEntered.await(1, TimeUnit.SECONDS));

        coordinator.cancelAll();
        releaseStop.countDown();
        runner.join(1000L);

        verify(lifecycleCoordinator).stopDevice("dev-running-close");
        verify(lifecycleCoordinator, never()).startDevice("dev-running-close");
        verify(timeSliceConfigCoordinator, never()).adjustTimeSlicesAfterWorkloadChange();
        assertEquals(0, coordinator.pendingTaskCountForTest());
    }

    @Test
    void differentDeviceRestartsShouldNotBlockEachOtherOnCoordinatorLock() throws Exception {
        DeviceLifecycleCoordinator lifecycleCoordinator = mock(DeviceLifecycleCoordinator.class);
        TimeSliceConfigCoordinator timeSliceConfigCoordinator = mock(TimeSliceConfigCoordinator.class);
        CapturingScheduledExecutor scheduledExecutor = new CapturingScheduledExecutor();
        CountDownLatch startAEntered = new CountDownLatch(1);
        CountDownLatch releaseStartA = new CountDownLatch(1);
        CountDownLatch startBEntered = new CountDownLatch(1);
        when(lifecycleCoordinator.isDeviceRunning("dev-restart-a")).thenReturn(true);
        when(lifecycleCoordinator.isDeviceRunning("dev-restart-b")).thenReturn(true);
        when(lifecycleCoordinator.stopDevice("dev-restart-a")).thenReturn(true);
        when(lifecycleCoordinator.stopDevice("dev-restart-b")).thenReturn(true);
        doAnswer(invocation -> {
            startAEntered.countDown();
            releaseStartA.await(1, TimeUnit.SECONDS);
            return true;
        }).when(lifecycleCoordinator).startDevice("dev-restart-a");
        doAnswer(invocation -> {
            startBEntered.countDown();
            return true;
        }).when(lifecycleCoordinator).startDevice("dev-restart-b");
        ConfigRestartCoordinator coordinator = new ConfigRestartCoordinator(
                lifecycleCoordinator,
                timeSliceConfigCoordinator,
                scheduledExecutor);

        coordinator.handleConfigUpdate(event("device", "dev-restart-a"));
        coordinator.handleConfigUpdate(event("device", "dev-restart-b"));
        Thread runnerA = new Thread(() -> scheduledExecutor.tasks.get(0).runIfNotCancelled());
        Thread runnerB = new Thread(() -> scheduledExecutor.tasks.get(1).runIfNotCancelled());
        runnerA.start();
        assertTrue(startAEntered.await(1, TimeUnit.SECONDS));
        runnerB.start();

        assertTrue(startBEntered.await(1, TimeUnit.SECONDS));
        releaseStartA.countDown();
        runnerA.join(1000L);
        runnerB.join(1000L);

        verify(lifecycleCoordinator).startDevice("dev-restart-a");
        verify(lifecycleCoordinator).startDevice("dev-restart-b");
        assertEquals(0, coordinator.pendingTaskCountForTest());
        assertEquals(0, coordinator.activeStartPhaseCountForTest());
    }

    @Test
    void cancelAllAfterStartAlreadyEnteredShouldNotDeadlock() throws Exception {
        DeviceLifecycleCoordinator lifecycleCoordinator = mock(DeviceLifecycleCoordinator.class);
        TimeSliceConfigCoordinator timeSliceConfigCoordinator = mock(TimeSliceConfigCoordinator.class);
        CapturingScheduledExecutor scheduledExecutor = new CapturingScheduledExecutor();
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        CountDownLatch cancelAllReturned = new CountDownLatch(1);
        when(lifecycleCoordinator.isDeviceRunning("dev-start-entered-close")).thenReturn(true);
        when(lifecycleCoordinator.stopDevice("dev-start-entered-close")).thenReturn(true);
        doAnswer(invocation -> {
            startEntered.countDown();
            releaseStart.await(1, TimeUnit.SECONDS);
            return true;
        }).when(lifecycleCoordinator).startDevice("dev-start-entered-close");
        ConfigRestartCoordinator coordinator = new ConfigRestartCoordinator(
                lifecycleCoordinator,
                timeSliceConfigCoordinator,
                scheduledExecutor);

        coordinator.handleConfigUpdate(event("device", "dev-start-entered-close"));
        Thread runner = new Thread(() -> scheduledExecutor.tasks.get(0).runIfNotCancelled());
        runner.start();
        assertTrue(startEntered.await(1, TimeUnit.SECONDS));
        Thread closer = new Thread(() -> {
            coordinator.cancelAll();
            cancelAllReturned.countDown();
        });
        closer.start();

        assertTrue(cancelAllReturned.await(1, TimeUnit.SECONDS));
        releaseStart.countDown();
        runner.join(1000L);
        closer.join(1000L);

        verify(lifecycleCoordinator).startDevice("dev-start-entered-close");
        assertEquals(0, coordinator.pendingTaskCountForTest());
        assertEquals(0, coordinator.activeStartPhaseCountForTest());
    }

    @Test
    void cancelAllShouldBeIdempotent() {
        DeviceLifecycleCoordinator lifecycleCoordinator = mock(DeviceLifecycleCoordinator.class);
        TimeSliceConfigCoordinator timeSliceConfigCoordinator = mock(TimeSliceConfigCoordinator.class);
        CapturingScheduledExecutor scheduledExecutor = new CapturingScheduledExecutor();
        ConfigRestartCoordinator coordinator = new ConfigRestartCoordinator(
                lifecycleCoordinator,
                timeSliceConfigCoordinator,
                scheduledExecutor);

        coordinator.cancelAll();
        coordinator.cancelAll();

        assertEquals(0, coordinator.pendingTaskCountForTest());
    }

    @Test
    void restartTaskShouldCleanupMapWhenStopFails() {
        DeviceLifecycleCoordinator lifecycleCoordinator = mock(DeviceLifecycleCoordinator.class);
        TimeSliceConfigCoordinator timeSliceConfigCoordinator = mock(TimeSliceConfigCoordinator.class);
        CapturingScheduledExecutor scheduledExecutor = new CapturingScheduledExecutor();
        when(lifecycleCoordinator.isDeviceRunning("dev-fail")).thenReturn(true);
        doThrow(new IllegalStateException("stop failed")).when(lifecycleCoordinator).stopDevice("dev-fail");
        ConfigRestartCoordinator coordinator = new ConfigRestartCoordinator(
                lifecycleCoordinator,
                timeSliceConfigCoordinator,
                scheduledExecutor);

        coordinator.handleConfigUpdate(event("device", "dev-fail"));
        scheduledExecutor.tasks.get(0).runIfNotCancelled();

        assertEquals(0, coordinator.pendingTaskCountForTest());
        verify(timeSliceConfigCoordinator, never()).adjustTimeSlicesAfterWorkloadChange();
    }

    @Test
    void restartTaskShouldNotStartWhenStopReturnsFalse() {
        DeviceLifecycleCoordinator lifecycleCoordinator = mock(DeviceLifecycleCoordinator.class);
        TimeSliceConfigCoordinator timeSliceConfigCoordinator = mock(TimeSliceConfigCoordinator.class);
        CapturingScheduledExecutor scheduledExecutor = new CapturingScheduledExecutor();
        when(lifecycleCoordinator.isDeviceRunning("dev-stop-false")).thenReturn(true);
        when(lifecycleCoordinator.stopDevice("dev-stop-false")).thenReturn(false);
        ConfigRestartCoordinator coordinator = new ConfigRestartCoordinator(
                lifecycleCoordinator,
                timeSliceConfigCoordinator,
                scheduledExecutor);

        coordinator.handleConfigUpdate(event("device", "dev-stop-false"));
        scheduledExecutor.tasks.get(0).runIfNotCancelled();

        assertEquals(0, coordinator.pendingTaskCountForTest());
        verify(lifecycleCoordinator, never()).startDevice("dev-stop-false");
        verify(timeSliceConfigCoordinator, never()).adjustTimeSlicesAfterWorkloadChange();
    }

    @Test
    void restartTaskShouldNotAdjustTimeSlicesWhenStartReturnsFalse() {
        DeviceLifecycleCoordinator lifecycleCoordinator = mock(DeviceLifecycleCoordinator.class);
        TimeSliceConfigCoordinator timeSliceConfigCoordinator = mock(TimeSliceConfigCoordinator.class);
        CapturingScheduledExecutor scheduledExecutor = new CapturingScheduledExecutor();
        when(lifecycleCoordinator.isDeviceRunning("dev-start-false")).thenReturn(true);
        when(lifecycleCoordinator.stopDevice("dev-start-false")).thenReturn(true);
        when(lifecycleCoordinator.startDevice("dev-start-false")).thenReturn(false);
        ConfigRestartCoordinator coordinator = new ConfigRestartCoordinator(
                lifecycleCoordinator,
                timeSliceConfigCoordinator,
                scheduledExecutor);

        coordinator.handleConfigUpdate(event("device", "dev-start-false"));
        scheduledExecutor.tasks.get(0).runIfNotCancelled();

        assertEquals(0, coordinator.pendingTaskCountForTest());
        verify(lifecycleCoordinator).stopDevice("dev-start-false");
        verify(lifecycleCoordinator).startDevice("dev-start-false");
        verify(timeSliceConfigCoordinator, never()).adjustTimeSlicesAfterWorkloadChange();
    }

    private ConfigUpdateEvent event(String configType, String deviceId) {
        ConfigUpdateEvent event = new ConfigUpdateEvent();
        event.setConfigType(configType);
        event.setDeviceId(deviceId);
        return event;
    }

    private void runCapturingFailure(Runnable runnable, AtomicReference<Throwable> failure) {
        try {
            runnable.run();
        } catch (Throwable throwable) {
            failure.compareAndSet(null, throwable);
        }
    }

    private static class CapturingScheduledExecutor implements ScheduledExecutorService {
        protected final List<CapturedFuture> tasks = Collections.synchronizedList(new ArrayList<>());

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            CapturedFuture future = new CapturedFuture(command, delay, unit);
            tasks.add(future);
            return future;
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException("测试未使用 Callable 调度");
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            throw new UnsupportedOperationException("测试未使用固定频率调度");
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException("测试未使用固定延迟调度");
        }

        @Override
        public <T> Future<T> submit(Callable<T> task) {
            throw new UnsupportedOperationException("测试未使用 submit");
        }

        @Override
        public <T> Future<T> submit(Runnable task, T result) {
            throw new UnsupportedOperationException("测试未使用 submit");
        }

        @Override
        public Future<?> submit(Runnable task) {
            throw new UnsupportedOperationException("测试未使用 submit");
        }

        @Override
        public <T> List<Future<T>> invokeAll(java.util.Collection<? extends Callable<T>> tasks) {
            throw new UnsupportedOperationException("测试未使用批量提交");
        }

        @Override
        public <T> List<Future<T>> invokeAll(
                java.util.Collection<? extends Callable<T>> tasks,
                long timeout,
                TimeUnit unit) {
            throw new UnsupportedOperationException("测试未使用批量提交");
        }

        @Override
        public <T> T invokeAny(java.util.Collection<? extends Callable<T>> tasks) {
            throw new UnsupportedOperationException("测试未使用批量提交");
        }

        @Override
        public <T> T invokeAny(java.util.Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException("测试未使用批量提交");
        }

        @Override
        public void execute(Runnable command) {
            throw new UnsupportedOperationException("测试未使用 execute");
        }
    }

    private static final class BlockingFirstScheduleExecutor extends CapturingScheduledExecutor {
        private final CountDownLatch firstScheduleEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFirstSchedule = new CountDownLatch(1);
        private int scheduleCalls;

        @Override
        public synchronized ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            scheduleCalls++;
            if (scheduleCalls == 1) {
                CapturedFuture future = new CapturedFuture(command, delay, unit);
                tasks.add(future);
                firstScheduleEntered.countDown();
                try {
                    releaseFirstSchedule.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return future;
            }
            return super.schedule(command, delay, unit);
        }

        private boolean awaitFirstScheduleEntered() throws InterruptedException {
            return firstScheduleEntered.await(1, TimeUnit.SECONDS);
        }

        private void releaseFirstSchedule() {
            releaseFirstSchedule.countDown();
        }
    }

    private static final class CapturedFuture implements ScheduledFuture<Object> {
        private final Runnable command;
        private final long delay;
        private final TimeUnit unit;
        private boolean cancelled;
        private boolean done;

        private CapturedFuture(Runnable command, long delay, TimeUnit unit) {
            this.command = command;
            this.delay = delay;
            this.unit = unit;
        }

        private void runIfNotCancelled() {
            if (!cancelled) {
                runEvenIfCancelled();
            }
        }

        private void runEvenIfCancelled() {
            command.run();
            done = true;
        }

        @Override
        public long getDelay(TimeUnit targetUnit) {
            return targetUnit.convert(delay, unit);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(getDelay(TimeUnit.MILLISECONDS), other.getDelay(TimeUnit.MILLISECONDS));
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return done || cancelled;
        }

        @Override
        public Object get() throws InterruptedException, ExecutionException {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            return null;
        }
    }
}
