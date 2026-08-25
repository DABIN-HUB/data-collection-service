package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.core.config.CollectorProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TimeSliceSchedulingCoordinatorTest {

    @Test
    void startShouldUseFixedDelayAndCancelPreviousSchedule() {
        CollectorProperties properties = new CollectorProperties();
        properties.getScheduler().setDueScanIntervalMs(500);
        properties.getScheduler().setMinTimeSliceIntervalMs(50);
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
    void phaseWheelTaskShouldAdvanceSlicesWithCapturedRevision() {
        CollectorProperties properties = new CollectorProperties();
        properties.getScheduler().setDueScanIntervalMs(500);
        properties.getScheduler().setMinTimeSliceIntervalMs(50);
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
}
