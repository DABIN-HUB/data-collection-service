package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.config.CollectorProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TimeSliceSchedulingCoordinatorTest {

    @Test
    void startShouldUseFixedDelayAndCancelPreviousSchedule() {
        CollectorProperties properties = newProperties();
        SchedulerRuntimeState runtimeState = new SchedulerRuntimeState();
        runtimeState.initializeTimeSlices(4, 500);
        PerformanceMonitor performanceMonitor = new PerformanceMonitor();
        TimeSliceExecutionCoordinator executionCoordinator = mock(TimeSliceExecutionCoordinator.class);
        ScheduledExecutorService scheduledExecutor = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> firstFuture = mock(ScheduledFuture.class);
        ScheduledFuture<?> secondFuture = mock(ScheduledFuture.class);
        when(scheduledExecutor.scheduleWithFixedDelay(
                any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenReturn((ScheduledFuture) firstFuture, (ScheduledFuture) secondFuture);
        TimeSliceSchedulingCoordinator coordinator = new TimeSliceSchedulingCoordinator(
                properties,
                runtimeState,
                performanceMonitor,
                executionCoordinator,
                scheduledExecutor,
                System::nanoTime);

        coordinator.startTimeSliceScheduling();
        coordinator.startTimeSliceScheduling();

        verify(firstFuture).cancel(false);
        ArgumentCaptor<Long> delayCaptor = ArgumentCaptor.forClass(Long.class);
        verify(scheduledExecutor, org.mockito.Mockito.times(2)).scheduleWithFixedDelay(
                any(Runnable.class),
                eq(0L),
                delayCaptor.capture(),
                eq(TimeUnit.MILLISECONDS));
        assertEquals(125L, delayCaptor.getAllValues().get(0));
        assertEquals(125L, delayCaptor.getAllValues().get(1));
    }

    @Test
    void concurrentStartShouldNotLeaveOrphanPhaseWheelFuture() throws Exception {
        CollectorProperties properties = newProperties();
        SchedulerRuntimeState runtimeState = new SchedulerRuntimeState();
        runtimeState.initializeTimeSlices(2, 500);
        BlockingFirstScheduleExecutor scheduledExecutor = new BlockingFirstScheduleExecutor();
        TimeSliceSchedulingCoordinator coordinator = new TimeSliceSchedulingCoordinator(
                properties,
                runtimeState,
                new PerformanceMonitor(),
                mock(TimeSliceExecutionCoordinator.class),
                scheduledExecutor,
                System::nanoTime);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread first = new Thread(() -> runCapturingFailure(coordinator::startTimeSliceScheduling, failure));
        Thread second = new Thread(() -> runCapturingFailure(coordinator::startTimeSliceScheduling, failure));

        first.start();
        assertTrue(scheduledExecutor.awaitFirstScheduleEntered());
        second.start();
        scheduledExecutor.releaseFirstSchedule();
        first.join(1000L);
        second.join(1000L);

        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        assertEquals(1, scheduledExecutor.activeFutureCount());
        assertTrue(coordinator.isSchedulingActive());
    }

    @Test
    void cancelShouldClearCurrentPhaseWheelFuture() {
        CollectorProperties properties = newProperties();
        SchedulerRuntimeState runtimeState = new SchedulerRuntimeState();
        runtimeState.initializeTimeSlices(1, 500);
        CapturingScheduledExecutor scheduledExecutor = new CapturingScheduledExecutor();
        TimeSliceSchedulingCoordinator coordinator = new TimeSliceSchedulingCoordinator(
                properties,
                runtimeState,
                new PerformanceMonitor(),
                mock(TimeSliceExecutionCoordinator.class),
                scheduledExecutor,
                System::nanoTime);

        coordinator.startTimeSliceScheduling();
        assertEquals(1, scheduledExecutor.activeFutureCount());
        assertTrue(coordinator.isSchedulingActive());

        coordinator.cancelTimeSliceScheduling();

        assertEquals(0, scheduledExecutor.activeFutureCount());
        assertFalse(coordinator.isSchedulingActive());
    }

    @Test
    void phaseWheelTaskShouldAdvanceSlicesWithCapturedRevision() {
        CollectorProperties properties = newProperties();
        SchedulerRuntimeState runtimeState = new SchedulerRuntimeState();
        runtimeState.initializeTimeSlices(2, 500);
        PerformanceMonitor performanceMonitor = new PerformanceMonitor();
        TimeSliceExecutionCoordinator executionCoordinator = mock(TimeSliceExecutionCoordinator.class);
        ScheduledExecutorService scheduledExecutor = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> scheduledFuture = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        when(scheduledExecutor.scheduleWithFixedDelay(
                runnableCaptor.capture(), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenReturn((ScheduledFuture) scheduledFuture);
        AtomicLong now = new AtomicLong(0L);
        TimeSliceSchedulingCoordinator coordinator = new TimeSliceSchedulingCoordinator(
                properties,
                runtimeState,
                performanceMonitor,
                executionCoordinator,
                scheduledExecutor,
                now::get);

        long capturedRevision = runtimeState.getTimeSliceRevision();
        coordinator.startTimeSliceScheduling();
        runtimeState.updateTimeSliceConfig(2, 500);
        runnableCaptor.getValue().run();
        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(250L));
        runnableCaptor.getValue().run();

        verify(executionCoordinator).executeTimeSlice(0, capturedRevision);
        verify(executionCoordinator).executeTimeSlice(1, capturedRevision);
    }

    @Test
    void oldRevisionTaskShouldNotSubmitBatchAfterRestart() {
        CollectorProperties properties = newProperties();
        SchedulerRuntimeState runtimeState = new SchedulerRuntimeState();
        runtimeState.initializeTimeSlices(1, 500);
        DeviceBatchExecutor batchExecutor = mock(DeviceBatchExecutor.class);
        TimeSliceExecutionCoordinator executionCoordinator = new TimeSliceExecutionCoordinator(
                runtimeState,
                new PerformanceMonitor(),
                batchExecutor,
                System::nanoTime);
        CapturingScheduledExecutor scheduledExecutor = new CapturingScheduledExecutor();
        TimeSliceSchedulingCoordinator coordinator = new TimeSliceSchedulingCoordinator(
                properties,
                runtimeState,
                new PerformanceMonitor(),
                executionCoordinator,
                scheduledExecutor,
                System::nanoTime);
        String deviceId = "dev-old-revision";
        DataPoint point = new DataPoint();
        point.setDeviceId(deviceId);
        point.setPointId("p1");
        long generation = 1L;
        runtimeState.markRunning(deviceId, generation);
        DeviceBatchTask oldTask = new DeviceBatchTask(
                deviceId,
                List.of(point),
                0,
                generation,
                runtimeState.getTimeSliceRevision());
        runtimeState.addBatchTasks(List.of(oldTask));

        coordinator.startTimeSliceScheduling();
        CapturedFuture oldFuture = scheduledExecutor.futures.get(0);
        runtimeState.updateTimeSliceConfig(1, 500);
        coordinator.startTimeSliceScheduling();
        oldFuture.runEvenIfCancelled();

        verify(batchExecutor, never()).submit(any(DeviceBatchTask.class), anyLong());
    }

    private CollectorProperties newProperties() {
        CollectorProperties properties = new CollectorProperties();
        properties.getScheduler().setDueScanIntervalMs(500);
        properties.getScheduler().setMinTimeSliceIntervalMs(50);
        return properties;
    }

    private void runCapturingFailure(Runnable runnable, AtomicReference<Throwable> failure) {
        try {
            runnable.run();
        } catch (Throwable throwable) {
            failure.compareAndSet(null, throwable);
        }
    }

    private static class CapturingScheduledExecutor implements ScheduledExecutorService {
        protected final List<CapturedFuture> futures = Collections.synchronizedList(new ArrayList<>());

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
            CapturedFuture future = new CapturedFuture(command, delay, unit);
            futures.add(future);
            return future;
        }

        int activeFutureCount() {
            synchronized (futures) {
                return (int) futures.stream().filter(future -> !future.isCancelled() && !future.isDone()).count();
            }
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
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException("测试未使用一次性调度");
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
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
            scheduleCalls++;
            if (scheduleCalls == 1) {
                CapturedFuture future = new CapturedFuture(command, delay, unit);
                futures.add(future);
                firstScheduleEntered.countDown();
                try {
                    releaseFirstSchedule.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return future;
            }
            return super.scheduleWithFixedDelay(command, initialDelay, delay, unit);
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
            return done;
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
