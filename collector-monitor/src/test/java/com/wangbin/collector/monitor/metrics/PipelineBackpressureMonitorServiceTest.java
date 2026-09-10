package com.wangbin.collector.monitor.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.core.cache.config.TelemetryStreamProperties;
import com.wangbin.collector.core.cache.ingress.TelemetryIngressBuffer;
import com.wangbin.collector.core.cache.ingress.TelemetryIngressBufferMetrics;
import com.wangbin.collector.core.cache.ingress.TelemetryIngressBufferProperties;
import com.wangbin.collector.core.cache.service.StreamWriteBuffer;
import com.wangbin.collector.core.cache.service.StreamWriteBufferMetrics;
import com.wangbin.collector.core.report.config.ReportProperties;
import com.wangbin.collector.core.report.outbox.CloudOutboxService;
import com.wangbin.collector.core.report.outbox.CloudOutboxSnapshot;
import com.wangbin.collector.storage.buffer.HistoryBufferMetrics;
import com.wangbin.collector.storage.buffer.HistoryBufferProperties;
import com.wangbin.collector.storage.buffer.HistoryWriteBuffer;
import com.wangbin.collector.storage.config.TdengineProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PipelineBackpressureMonitorServiceTest {

    @Test
    void healthySnapshotShouldUseLowQueuesAndNoDeadLetters() {
        TestContext context = new TestContext();
        PipelineBackpressureSnapshot snapshot = context.service().getSnapshot();

        assertThat(snapshot.status()).isEqualTo(PipelineStatus.HEALTHY);
        assertThat(snapshot.ingress().status()).isEqualTo(PipelineStatus.HEALTHY);
        assertThat(snapshot.stream().status()).isEqualTo(PipelineStatus.HEALTHY);
        assertThat(snapshot.history().status()).isEqualTo(PipelineStatus.HEALTHY);
        assertThat(snapshot.risks()).isEmpty();
    }

    @Test
    void warningSnapshotShouldFollowBoundedQueueThreshold() {
        TestContext context = new TestContext();
        context.ingressMetrics = new TelemetryIngressBufferMetrics(0L, 0L, 0L, 70, 100,
                0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);

        PipelineBackpressureSnapshot snapshot = context.service().getSnapshot();

        assertThat(snapshot.status()).isEqualTo(PipelineStatus.WARNING);
        assertThat(snapshot.ingress().localUtilization()).isEqualTo(0.7D);
        assertThat(snapshot.risks()).contains("INGRESS_LOCAL_QUEUE_HIGH");
    }

    @Test
    void dangerSnapshotShouldFollowBoundedQueueThreshold() {
        TestContext context = new TestContext();
        context.streamMetrics = new StreamWriteBufferMetrics(0L, 0L, 0L, 90, 90, 100,
                0L, 0L, 0, 0, 0, 0L, 0L, 0L,
                0D, 0D, 0D, 0L, 0L);

        PipelineBackpressureSnapshot snapshot = context.service().getSnapshot();

        assertThat(snapshot.status()).isEqualTo(PipelineStatus.DANGER);
        assertThat(snapshot.stream().bufferUtilization()).isEqualTo(0.9D);
        assertThat(snapshot.risks()).contains("STREAM_BUFFER_HIGH");
    }

    @Test
    void historyDeadLetterShouldBeDanger() {
        TestContext context = new TestContext();
        context.historyMetrics = new HistoryBufferMetrics(0L, 0L, 1L, 0, 100);

        PipelineBackpressureSnapshot snapshot = context.service().getSnapshot();

        assertThat(snapshot.history().status()).isEqualTo(PipelineStatus.DANGER);
        assertThat(snapshot.status()).isEqualTo(PipelineStatus.DANGER);
        assertThat(snapshot.risks()).contains("HISTORY_DEAD_LETTER");
    }

    @Test
    void unknownRedisMetricShouldRemainUnknownAndNotZeroHealthy() {
        TestContext context = new TestContext();
        context.ingressMetrics = new TelemetryIngressBufferMetrics(-1L, -1L, -1L, 0, 100,
                0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);

        PipelineBackpressureSnapshot snapshot = context.service().getSnapshot();

        assertThat(snapshot.ingress().status()).isEqualTo(PipelineStatus.UNKNOWN);
        assertThat(snapshot.ingress().redisPending()).isEqualTo(-1L);
        assertThat(snapshot.risks()).contains("INGRESS_REDIS_UNAVAILABLE");
    }

    @Test
    void disabledCloudShouldBeDisabledAndNotOverallDanger() {
        TestContext context = new TestContext();
        context.cloudSnapshot = new CloudOutboxSnapshot(false, 0L, 0L, 0L);

        PipelineBackpressureSnapshot snapshot = context.service().getSnapshot();

        assertThat(snapshot.cloud().status()).isEqualTo(PipelineStatus.DISABLED);
        assertThat(snapshot.status()).isEqualTo(PipelineStatus.HEALTHY);
    }

    @Test
    void partialSourceFailureShouldKeepOtherStagesAvailable() {
        TestContext context = new TestContext();
        context.ingressThrows = true;

        PipelineBackpressureSnapshot snapshot = context.service().getSnapshot();

        assertThat(snapshot.ingress().status()).isEqualTo(PipelineStatus.UNKNOWN);
        assertThat(snapshot.stream().status()).isEqualTo(PipelineStatus.HEALTHY);
        assertThat(snapshot.history().status()).isEqualTo(PipelineStatus.HEALTHY);
        assertThat(snapshot.risks()).contains("INGRESS_SOURCE_FAILURE");
    }

    @Test
    void cachedSnapshotShouldAvoidRepeatedRawMetricsCallsAcrossGaugeReads() throws Exception {
        TestContext context = new TestContext();
        PipelineBackpressureMonitorService service = context.service();
        PipelineMetricsBinder binder = new PipelineMetricsBinder(service);
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        binder.bindTo(registry);

        for (int index = 0; index < 20; index++) {
            registry.get("collector.pipeline.queue.size").tag("stage", "ingress").tag("type", "local").gauge().value();
            registry.get("collector.pipeline.queue.utilization").tag("stage", "stream").tag("type", "local").gauge().value();
            registry.get("collector.pipeline.backlog").tag("stage", "history").tag("type", "current").gauge().value();
        }

        assertThat(context.ingressCalls).hasValue(1);
        assertThat(context.streamCalls).hasValue(1);
        assertThat(context.historyCalls).hasValue(1);
    }

    private static class TestContext {
        final TelemetryIngressBuffer ingress = mock(TelemetryIngressBuffer.class);
        final StreamWriteBuffer stream = mock(StreamWriteBuffer.class);
        final HistoryWriteBuffer history = mock(HistoryWriteBuffer.class);
        final CloudOutboxService cloud = mock(CloudOutboxService.class);
        final SystemResourceMonitorService resources = mock(SystemResourceMonitorService.class);
        final AtomicInteger ingressCalls = new AtomicInteger();
        final AtomicInteger streamCalls = new AtomicInteger();
        final AtomicInteger historyCalls = new AtomicInteger();
        TelemetryIngressBufferMetrics ingressMetrics = new TelemetryIngressBufferMetrics(0L, 0L, 0L, 1, 100,
                0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
        StreamWriteBufferMetrics streamMetrics = StreamWriteBufferMetrics.empty();
        HistoryBufferMetrics historyMetrics = new HistoryBufferMetrics(0L, 0L, 0L, 0, 100);
        CloudOutboxSnapshot cloudSnapshot = new CloudOutboxSnapshot(true, 0L, 0L, 0L);
        boolean ingressThrows;

        PipelineBackpressureMonitorService service() {
            when(ingress.metrics()).thenAnswer(invocation -> {
                ingressCalls.incrementAndGet();
                if (ingressThrows) {
                    throw new IllegalStateException("boom");
                }
                return ingressMetrics;
            });
            when(stream.metrics()).thenAnswer(invocation -> {
                streamCalls.incrementAndGet();
                return streamMetrics;
            });
            when(history.metrics()).thenAnswer(invocation -> {
                historyCalls.incrementAndGet();
                return historyMetrics;
            });
            when(cloud.snapshot()).thenAnswer(invocation -> cloudSnapshot);
            when(resources.getResources()).thenReturn(resourcesSnapshot());
            return new PipelineBackpressureMonitorService(provider(ingress), provider(stream), provider(history), provider(cloud),
                    resources, new TelemetryIngressBufferProperties(), new TelemetryStreamProperties(),
                    historyProperties(), tdengineProperties(), new ReportProperties(),
                    Clock.fixed(Instant.ofEpochMilli(1788998400000L), ZoneId.of("UTC")), Duration.ofSeconds(5));
        }

        private HistoryBufferProperties historyProperties() {
            HistoryBufferProperties properties = new HistoryBufferProperties();
            properties.setLocalQueueCapacity(100);
            return properties;
        }

        private TdengineProperties tdengineProperties() {
            TdengineProperties properties = new TdengineProperties();
            properties.setEnabled(true);
            return properties;
        }

        private SystemResourceSnapshot resourcesSnapshot() {
            SystemResourceSnapshot.ThreadPoolSnapshot pool = SystemResourceSnapshot.ThreadPoolSnapshot.builder()
                    .corePoolSize(1)
                    .maxPoolSize(1)
                    .activeCount(0)
                    .queueSize(0)
                    .queueCapacity(100)
                    .queueUtilization(0D)
                    .completedTaskCount(0L)
                    .rejectedCount(0L)
                    .build();
            return SystemResourceSnapshot.builder()
                    .threadPools(Map.of(
                            "telemetryCacheStageExecutor", pool,
                            "telemetryStreamStageExecutor", pool,
                            "telemetryStreamWriteExecutor", pool,
                            "telemetryHistoryStageExecutor", pool,
                            "telemetryReportStageExecutor", pool))
                    .build();
        }

        @SuppressWarnings("unchecked")
        private <T> ObjectProvider<T> provider(T value) {
            ObjectProvider<T> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(value);
            return provider;
        }
    }
}
