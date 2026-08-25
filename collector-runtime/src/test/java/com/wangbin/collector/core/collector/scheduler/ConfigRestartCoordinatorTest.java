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
    void localDeleteShouldCancelPendingRestartAndStopRunningDevice() {
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

        verify(lifecycleCoordinator).stopDevice("dev-delete");
        verify(lifecycleCoordinator, never()).startDevice("dev-delete");
        assertEquals(1, scheduledExecutor.tasks.size());
        assertTrue(scheduledExecutor.tasks.get(0).isCancelled());
        assertEquals(0, coordinator.pendingTaskCountForTest());
    }

    @Test
    void configUpdateWhileStartingShouldStopStartingDeviceAndScheduleStartOnly() {
        DeviceLifecycleCoordinator lifecycleCoordinator = mock(DeviceLifecycleCoordinator.class);
        TimeSliceConfigCoordinator timeSliceConfigCoordinator = mock(TimeSliceConfigCoordinator.class);
        CapturingScheduledExecutor scheduledExecutor = new CapturingScheduledExecutor();
        when(lifecycleCoordinator.isDeviceRunning("dev-starting-update")).thenReturn(false);
        when(lifecycleCoordinator.isDeviceStarting("dev-starting-update")).thenReturn(true);
        when(lifecycleCoordinator.stopDevice("dev-starting-update")).thenReturn(true);
        when(lifecycleCoordinator.startDevice("dev-starting-update")).thenReturn(true);
        ConfigRestartCoordinator coordinator = new ConfigRestartCoordinator(
                lifecycleCoordinator,
                timeSliceConfigCoordinator,
                scheduledExecutor);

        coordinator.handleConfigUpdate(event("device", "dev-starting-update"));

        verify(lifecycleCoordinator).stopDevice("dev-starting-update");
        verify(lifecycleCoordinator, never()).startDevice("dev-starting-update");
        assertEquals(1, scheduledExecutor.tasks.size());

        scheduledExecutor.tasks.get(0).runIfNotCancelled();

        verify(lifecycleCoordinator).stopDevice("dev-starting-update");
        verify(lifecycleCoordinator).startDevice("dev-starting-update");
        verify(timeSliceConfigCoordinator).adjustTimeSlicesAfterWorkloadChange();
        assertEquals(0, coordinator.pendingTaskCountForTest());
    }

    @Test
    void localDeleteShouldStopStartingDeviceWithoutSchedulingRestart() {
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

        verify(lifecycleCoordinator).stopDevice("dev-starting-delete");
        verify(lifecycleCoordinator, never()).startDevice("dev-starting-delete");
        assertEquals(0, scheduledExecutor.tasks.size());
        assertEquals(0, coordinator.pendingTaskCountForTest());
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
    void restartTaskShouldNotAdjustTimeSlicesWhenStartReturnsFalse() {
        DeviceLifecycleCoordinator lifecycleCoordinator = mock(DeviceLifecycleCoordinator.class);
        TimeSliceConfigCoordinator timeSliceConfigCoordinator = mock(TimeSliceConfigCoordinator.class);
        CapturingScheduledExecutor scheduledExecutor = new CapturingScheduledExecutor();
        when(lifecycleCoordinator.isDeviceRunning("dev-start-false")).thenReturn(true);
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
