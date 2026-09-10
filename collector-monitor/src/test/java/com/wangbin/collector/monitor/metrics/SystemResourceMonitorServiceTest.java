package com.wangbin.collector.monitor.metrics;

import com.wangbin.collector.core.report.outbox.CloudOutboxService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SystemResourceMonitorServiceTest {

    @Test
    void boundedArrayBlockingQueueShouldExposeSizeCapacityAndUtilization() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        ThreadPoolExecutor executor = executor(new ArrayBlockingQueue<>(10));
        executor.getQueue().offer(() -> { });
        executor.getQueue().offer(() -> { });
        beanFactory.addBean("telemetryCacheStageExecutor", executor);
        SystemResourceMonitorService service = service(beanFactory);

        SystemResourceSnapshot.ThreadPoolSnapshot snapshot = service.getResources()
                .getThreadPools().get("telemetryCacheStageExecutor");

        assertThat(snapshot.getQueueSize()).isEqualTo(2);
        assertThat(snapshot.getQueueCapacity()).isEqualTo(10);
        assertThat(snapshot.getQueueUtilization()).isEqualTo(0.2D);
    }

    @Test
    void emptyQueueShouldExposeZeroUtilization() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("telemetryStreamStageExecutor", executor(new ArrayBlockingQueue<>(10)));
        SystemResourceMonitorService service = service(beanFactory);

        SystemResourceSnapshot.ThreadPoolSnapshot snapshot = service.getResources()
                .getThreadPools().get("telemetryStreamStageExecutor");

        assertThat(snapshot.getQueueSize()).isZero();
        assertThat(snapshot.getQueueCapacity()).isEqualTo(10);
        assertThat(snapshot.getQueueUtilization()).isEqualTo(0D);
    }

    @Test
    void ninetyPercentQueueShouldExposeCorrectUtilization() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        ThreadPoolExecutor executor = executor(new ArrayBlockingQueue<>(10));
        for (int index = 0; index < 9; index++) {
            executor.getQueue().offer(() -> { });
        }
        beanFactory.addBean("telemetryHistoryStageExecutor", executor);
        SystemResourceMonitorService service = service(beanFactory);

        SystemResourceSnapshot.ThreadPoolSnapshot snapshot = service.getResources()
                .getThreadPools().get("telemetryHistoryStageExecutor");

        assertThat(snapshot.getQueueSize()).isEqualTo(9);
        assertThat(snapshot.getQueueCapacity()).isEqualTo(10);
        assertThat(snapshot.getQueueUtilization()).isEqualTo(0.9D);
    }

    @Test
    void unboundedQueueShouldExposeUnknownCapacityAndUtilization() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        ThreadPoolExecutor executor = executor(new LinkedBlockingQueue<>());
        beanFactory.addBean("telemetryReportStageExecutor", executor);
        SystemResourceMonitorService service = service(beanFactory);

        SystemResourceSnapshot.ThreadPoolSnapshot snapshot = service.getResources()
                .getThreadPools().get("telemetryReportStageExecutor");

        assertThat(snapshot.getQueueCapacity()).isEqualTo(-1);
        assertThat(snapshot.getQueueUtilization()).isEqualTo(-1D);
    }

    @Test
    void zeroCapacityQueueShouldAvoidDivisionByZero() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        ThreadPoolExecutor executor = executor(new SynchronousQueue<>());
        beanFactory.addBean("telemetryStreamWriteExecutor", executor);
        SystemResourceMonitorService service = service(beanFactory);

        SystemResourceSnapshot.ThreadPoolSnapshot snapshot = service.getResources()
                .getThreadPools().get("telemetryStreamWriteExecutor");

        assertThat(snapshot.getQueueCapacity()).isZero();
        assertThat(snapshot.getQueueUtilization()).isZero();
    }

    @Test
    void missingBeanShouldExposeUnknownSnapshot() {
        SystemResourceMonitorService service = service(new StaticListableBeanFactory());

        SystemResourceSnapshot.ThreadPoolSnapshot snapshot = service.getResources()
                .getThreadPools().get("telemetryCacheStageExecutor");

        assertThat(snapshot.getQueueSize()).isEqualTo(-1);
        assertThat(snapshot.getQueueCapacity()).isEqualTo(-1);
        assertThat(snapshot.getQueueUtilization()).isEqualTo(-1D);
    }

    @Test
    void threadPoolOnlyAccessorShouldNotReadCloudOutbox() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("telemetryCacheStageExecutor", executor(new ArrayBlockingQueue<>(10)));
        CloudOutboxService outboxService = mock(CloudOutboxService.class);
        SystemResourceMonitorService service = new SystemResourceMonitorService(beanFactory, outboxService);
        service.init();

        Map<String, SystemResourceSnapshot.ThreadPoolSnapshot> threadPools = service.getThreadPools();

        assertThat(threadPools).containsKey("telemetryCacheStageExecutor");
        verifyNoInteractions(outboxService);
    }

    @Test
    void getResourcesShouldUseSingleCoherentCloudSnapshot() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        CloudOutboxService outboxService = mock(CloudOutboxService.class);
        when(outboxService.snapshot()).thenReturn(new com.wangbin.collector.core.report.outbox.CloudOutboxSnapshot(
                true, 11L, 2L, 3000L));
        SystemResourceMonitorService service = new SystemResourceMonitorService(beanFactory, outboxService);
        service.init();

        SystemResourceSnapshot snapshot = service.getResources();

        assertThat(snapshot.getOutboxPendingCount()).isEqualTo(11L);
        assertThat(snapshot.getOutboxIsolatedCount()).isEqualTo(2L);
        assertThat(snapshot.getOutboxOldestMessageAgeMillis()).isEqualTo(3000L);
        verify(outboxService).snapshot();
        verify(outboxService, never()).getPendingCount();
        verify(outboxService, never()).getIsolatedCount();
        verify(outboxService, never()).getOldestMessageAgeMillis();
    }

    private SystemResourceMonitorService service(StaticListableBeanFactory beanFactory) {
        CloudOutboxService outboxService = mock(CloudOutboxService.class);
        when(outboxService.snapshot()).thenReturn(new com.wangbin.collector.core.report.outbox.CloudOutboxSnapshot(
                true, 0L, 0L, 0L));
        SystemResourceMonitorService service = new SystemResourceMonitorService(beanFactory, outboxService);
        service.init();
        return service;
    }

    private ThreadPoolExecutor executor(java.util.concurrent.BlockingQueue<Runnable> queue) {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, queue);
    }
}
