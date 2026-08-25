package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.core.config.model.ConfigUpdateEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    void localDeleteShouldStopRunningDeviceWithoutSchedulingRestart() {
        DeviceLifecycleCoordinator lifecycleCoordinator = mock(DeviceLifecycleCoordinator.class);
        TimeSliceConfigCoordinator timeSliceConfigCoordinator = mock(TimeSliceConfigCoordinator.class);
        CapturingScheduledExecutor scheduledExecutor = new CapturingScheduledExecutor();
        when(lifecycleCoordinator.isDeviceRunning("dev-delete")).thenReturn(true);
        ConfigRestartCoordinator coordinator = new ConfigRestartCoordinator(
                lifecycleCoordinator,
                timeSliceConfigCoordinator,
                scheduledExecutor);

        coordinator.handleConfigUpdate(event("local-delete", "dev-delete"));

        verify(lifecycleCoordinator).stopDevice("dev-delete");
        verify(lifecycleCoordinator, never()).startDevice("dev-delete");
        assertEquals(0, scheduledExecutor.tasks.size());
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

    private ConfigUpdateEvent event(String configType, String deviceId) {
        ConfigUpdateEvent event = new ConfigUpdateEvent();
        event.setConfigType(configType);
        event.setDeviceId(deviceId);
        return event;
    }

    private static final class CapturingScheduledExecutor implements ScheduledExecutorService {
        private final List<CapturedFuture> tasks = new ArrayList<>();

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
        public <T> java.util.concurrent.Future<T> submit(Callable<T> task) {
            throw new UnsupportedOperationException("测试未使用 submit");
        }

        @Override
        public <T> java.util.concurrent.Future<T> submit(Runnable task, T result) {
            throw new UnsupportedOperationException("测试未使用 submit");
        }

        @Override
        public java.util.concurrent.Future<?> submit(Runnable task) {
            throw new UnsupportedOperationException("测试未使用 submit");
        }

        @Override
        public <T> List<java.util.concurrent.Future<T>> invokeAll(java.util.Collection<? extends Callable<T>> tasks) {
            throw new UnsupportedOperationException("测试未使用批量提交");
        }

        @Override
        public <T> List<java.util.concurrent.Future<T>> invokeAll(
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
                command.run();
                done = true;
            }
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
