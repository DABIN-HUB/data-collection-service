package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.core.config.CollectorProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
        ScheduledExecutorService scheduledExecutor = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> adjustFuture = mock(ScheduledFuture.class);
        ScheduledFuture<?> monitorFuture = mock(ScheduledFuture.class);
        ScheduledFuture<?> autoStartFuture = mock(ScheduledFuture.class);
        when(scheduledExecutor.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenReturn((ScheduledFuture) adjustFuture, (ScheduledFuture) monitorFuture);
        when(scheduledExecutor.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenReturn((ScheduledFuture) autoStartFuture);
        SchedulerMaintenanceCoordinator coordinator = new SchedulerMaintenanceCoordinator(
                properties,
                runtimeState,
                performanceMonitor,
                lifecycleCoordinator,
                configCoordinator,
                scheduledExecutor);

        coordinator.start();
        coordinator.cancel();

        verify(adjustFuture).cancel(false);
        verify(monitorFuture).cancel(false);
        verify(autoStartFuture).cancel(false);
    }
}
