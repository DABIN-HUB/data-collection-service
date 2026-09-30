package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.core.config.CollectorProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SchedulerMaintenanceCoordinatorTest {

    @Test
    void startShouldScheduleAdjustmentMonitoringAndAutoStart() {
        CollectorProperties properties = new CollectorProperties();
        properties.getScheduler().setDynamicAdjustIntervalMs(1234);
        SchedulerRuntimeState runtimeState = new SchedulerRuntimeState();
        runtimeState.initializeTimeSlices(1, 1000);
        PerformanceMonitor performanceMonitor = mock(PerformanceMonitor.class);
        DeviceLifecycleCoordinator lifecycleCoordinator = mock(DeviceLifecycleCoordinator.class);
        TimeSliceConfigCoordinator configCoordinator = mock(TimeSliceConfigCoordinator.class);
        ScheduledExecutorService scheduledExecutor = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> adjustFuture = mock(ScheduledFuture.class);
        ScheduledFuture<?> monitorFuture = mock(ScheduledFuture.class);
        ScheduledFuture<?> autoStartFuture = mock(ScheduledFuture.class);
        when(scheduledExecutor.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenReturn((ScheduledFuture) adjustFuture, (ScheduledFuture) monitorFuture);
        ArgumentCaptor<Runnable> autoStartCaptor = ArgumentCaptor.forClass(Runnable.class);
        when(scheduledExecutor.schedule(autoStartCaptor.capture(), anyLong(), any(TimeUnit.class)))
                .thenReturn((ScheduledFuture) autoStartFuture);
        SchedulerMaintenanceCoordinator coordinator = new SchedulerMaintenanceCoordinator(
                properties,
                runtimeState,
                performanceMonitor,
                lifecycleCoordinator,
                configCoordinator,
                scheduledExecutor);

        coordinator.start();
        autoStartCaptor.getValue().run();

        ArgumentCaptor<Long> initialDelayCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> periodCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<TimeUnit> unitCaptor = ArgumentCaptor.forClass(TimeUnit.class);
        verify(scheduledExecutor, times(2)).scheduleAtFixedRate(
                any(Runnable.class),
                initialDelayCaptor.capture(),
                periodCaptor.capture(),
                unitCaptor.capture());
        assertEquals(1234L, initialDelayCaptor.getAllValues().get(0));
        assertEquals(1234L, periodCaptor.getAllValues().get(0));
        assertEquals(TimeUnit.MILLISECONDS, unitCaptor.getAllValues().get(0));
        assertEquals(60L, initialDelayCaptor.getAllValues().get(1));
        assertEquals(60L, periodCaptor.getAllValues().get(1));
        assertEquals(TimeUnit.SECONDS, unitCaptor.getAllValues().get(1));
        verify(scheduledExecutor).schedule(any(Runnable.class), eq(5L), eq(TimeUnit.SECONDS));
        verify(lifecycleCoordinator).startAllDevices();
        verify(configCoordinator).adjustTimeSlicesAfterWorkloadChange();
    }

    @Test
    void cancelShouldCancelAllMaintenanceTasks() {
        CollectorProperties properties = new CollectorProperties();
        SchedulerRuntimeState runtimeState = new SchedulerRuntimeState();
        PerformanceMonitor performanceMonitor = mock(PerformanceMonitor.class);
        DeviceLifecycleCoordinator lifecycleCoordinator = mock(DeviceLifecycleCoordinator.class);
        TimeSliceConfigCoordinator configCoordinator = mock(TimeSliceConfigCoordinator.class);
        CapturingScheduledExecutor scheduledExecutor = new CapturingScheduledExecutor();
        SchedulerMaintenanceCoordinator coordinator = new SchedulerMaintenanceCoordinator(
                properties,
                runtimeState,
                performanceMonitor,
                lifecycleCoordinator,
                configCoordinator,
                scheduledExecutor);

        coordinator.start();
        coordinator.cancel();

        scheduledExecutor.periodicFutures.forEach(future -> assertTrue(future.isCancelled()));
        assertTrue(scheduledExecutor.delayedFutures.get(0).isCancelled());
        assertEquals(0, coordinator.pendingStartAllFutureCountForTest());
    }

    @Test
    void completedDelayedStartShouldNotRemainPending() {
        CollectorProperties properties = new CollectorProperties();
        SchedulerRuntimeState runtimeState = new SchedulerRuntimeState();
        PerformanceMonitor performanceMonitor = mock(PerformanceMonitor.class);
        DeviceLifecycleCoordinator lifecycleCoordinator = mock(DeviceLifecycleCoordinator.class);
        TimeSliceConfigCoordinator configCoordinator = mock(TimeSliceConfigCoordinator.class);
        CapturingScheduledExecutor scheduledExecutor = new CapturingScheduledExecutor();
        SchedulerMaintenanceCoordinator coordinator = new SchedulerMaintenanceCoordinator(
                properties,
                runtimeState,
                performanceMonitor,
                lifecycleCoordinator,
                configCoordinator,
                scheduledExecutor);

        coordinator.scheduleStartAllDevices(2, TimeUnit.SECONDS);
        assertEquals(1, coordinator.pendingStartAllFutureCountForTest());
        scheduledExecutor.delayedFutures.get(0).runIfNotCancelled();

        assertEquals(0, coordinator.pendingStartAllFutureCountForTest());
        verify(lifecycleCoordinator).startAllDevices();
        verify(configCoordinator).adjustTimeSlicesAfterWorkloadChange();
    }

    @Test
    void repeatedDelayedStartShouldReplacePendingStartTask() {
        CollectorProperties properties = new CollectorProperties();
        SchedulerRuntimeState runtimeState = new SchedulerRuntimeState();
        PerformanceMonitor performanceMonitor = mock(PerformanceMonitor.class);
        DeviceLifecycleCoordinator lifecycleCoordinator = mock(DeviceLifecycleCoordinator.class);
        TimeSliceConfigCoordinator configCoordinator = mock(TimeSliceConfigCoordinator.class);
        CapturingScheduledExecutor scheduledExecutor = new CapturingScheduledExecutor();
        SchedulerMaintenanceCoordinator coordinator = new SchedulerMaintenanceCoordinator(
                properties,
                runtimeState,
                performanceMonitor,
                lifecycleCoordinator,
                configCoordinator,
                scheduledExecutor);

        coordinator.scheduleStartAllDevices(2, TimeUnit.SECONDS);
        coordinator.scheduleStartAllDevices(3, TimeUnit.SECONDS);

        assertEquals(2, scheduledExecutor.delayedFutures.size());
        assertTrue(scheduledExecutor.delayedFutures.get(0).isCancelled());
        assertEquals(1, coordinator.pendingStartAllFutureCountForTest());
        scheduledExecutor.delayedFutures.forEach(CapturedFuture::runIfNotCancelled);
        verify(lifecycleCoordinator).startAllDevices();
        verify(configCoordinator).adjustTimeSlicesAfterWorkloadChange();
    }

    @Test
    void cancelledDelayedStartShouldNotRunAutoStart() {
        CollectorProperties properties = new CollectorProperties();
        SchedulerRuntimeState runtimeState = new SchedulerRuntimeState();
        PerformanceMonitor performanceMonitor = mock(PerformanceMonitor.class);
        DeviceLifecycleCoordinator lifecycleCoordinator = mock(DeviceLifecycleCoordinator.class);
        TimeSliceConfigCoordinator configCoordinator = mock(TimeSliceConfigCoordinator.class);
        CapturingScheduledExecutor scheduledExecutor = new CapturingScheduledExecutor();
        SchedulerMaintenanceCoordinator coordinator = new SchedulerMaintenanceCoordinator(
                properties,
                runtimeState,
                performanceMonitor,
                lifecycleCoordinator,
                configCoordinator,
                scheduledExecutor);

        coordinator.scheduleStartAllDevices(2, TimeUnit.SECONDS);
        coordinator.cancel();
        scheduledExecutor.delayedFutures.get(0).runIfNotCancelled();

        verify(lifecycleCoordinator, never()).startAllDevices();
        assertEquals(0, coordinator.pendingStartAllFutureCountForTest());
    }

    private static final class CapturingScheduledExecutor implements ScheduledExecutorService {
        private final List<CapturedFuture> periodicFutures = Collections.synchronizedList(new ArrayList<>());
        private final List<CapturedFuture> delayedFutures = Collections.synchronizedList(new ArrayList<>());

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            CapturedFuture future = new CapturedFuture(command, delay, unit);
            delayedFutures.add(future);
            return future;
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            CapturedFuture future = new CapturedFuture(command, period, unit);
            periodicFutures.add(future);
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
