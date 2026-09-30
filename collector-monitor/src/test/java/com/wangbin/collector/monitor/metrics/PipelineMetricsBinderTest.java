package com.wangbin.collector.monitor.metrics;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PipelineMetricsBinderTest {

    @Test
    void shouldRegisterSmallAlertGradeMetricSubset() {
        PipelineBackpressureMonitorService service = mock(PipelineBackpressureMonitorService.class);
        when(service.getSnapshot()).thenReturn(snapshot("device-a", "point-a"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new PipelineMetricsBinder(service).bindTo(registry);

        assertThat(registry.find("collector.pipeline.enabled").gauges()).isNotEmpty();
        assertThat(registry.find("collector.pipeline.queue.size").gauges()).isNotEmpty();
        assertThat(registry.find("collector.pipeline.queue.capacity").gauges()).isNotEmpty();
        assertThat(registry.find("collector.pipeline.queue.utilization").gauges()).isNotEmpty();
        assertThat(registry.find("collector.pipeline.backlog").gauges()).isNotEmpty();
        assertThat(registry.find("collector.pipeline.rejected").gauges()).isNotEmpty();
        assertThat(registry.find("collector.pipeline.dropped").gauges()).isNotEmpty();
        assertThat(registry.find("collector.pipeline.failures").gauges()).isNotEmpty();
        assertThat(registry.find("collector.pipeline.oldest.age").gauges()).isNotEmpty();
    }

    @Test
    void tagsShouldUseOnlyFixedLowCardinalityValues() {
        PipelineBackpressureMonitorService service = mock(PipelineBackpressureMonitorService.class);
        when(service.getSnapshot()).thenReturn(snapshot("device-a", "point-a"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new PipelineMetricsBinder(service).bindTo(registry);

        Set<String> allowedStages = Set.of("ingress", "stream", "history", "cloud",
                "cache_executor", "stream_executor", "stream_writer_executor", "history_executor", "report_executor");
        Set<String> allowedTypes = Set.of("state", "HEALTHY", "WARNING", "DANGER", "UNKNOWN", "DISABLED",
                "local", "current", "total", "live_flush", "outbox_pending", "outbox_isolated", "outbox", "executor");
        for (Meter meter : registry.getMeters()) {
            Meter.Id id = meter.getId();
            assertThat(id.getTag("stage")).isIn(allowedStages);
            assertThat(id.getTag("type")).isIn(allowedTypes);
            assertThat(id.getTag("deviceId")).isNull();
            assertThat(id.getTag("pointId")).isNull();
            assertThat(id.getTag("requestId")).isNull();
            assertThat(id.getTag("messageId")).isNull();
        }
    }

    @Test
    void dynamicDeviceAndPointIdsShouldNotChangeMeterCount() {
        PipelineBackpressureMonitorService service = mock(PipelineBackpressureMonitorService.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new PipelineMetricsBinder(service).bindTo(registry);
        int initialMeterCount = registry.getMeters().size();

        for (int device = 0; device < 100; device++) {
            when(service.getSnapshot()).thenReturn(snapshot("device-" + device, "point-" + device));
            registry.get("collector.pipeline.queue.size").tag("stage", "ingress").tag("type", "local").gauge().value();
        }

        assertThat(registry.getMeters()).hasSize(initialMeterCount);
    }

    private PipelineBackpressureSnapshot snapshot(String deviceId, String pointId) {
        PipelineBackpressureSnapshot.IngressSnapshot ingress = new PipelineBackpressureSnapshot.IngressSnapshot(
                true, PipelineStatus.HEALTHY, 1L, 0L, 0L, 1, 100, 0.01D,
                2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L);
        PipelineBackpressureSnapshot.StreamSnapshot stream = new PipelineBackpressureSnapshot.StreamSnapshot(
                true, PipelineStatus.HEALTHY, 1, 2, 100, 0.01D,
                3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L);
        PipelineBackpressureSnapshot.HistorySnapshot history = new PipelineBackpressureSnapshot.HistorySnapshot(
                true, PipelineStatus.HEALTHY, 1L, 0L, 0L, 1, 100, 0.01D,
                2L, 3L, 4L, 5L, 6L, 7L, 8L, 9, 10L, 0.01D);
        PipelineBackpressureSnapshot.CloudSnapshot cloud = new PipelineBackpressureSnapshot.CloudSnapshot(
                true, PipelineStatus.HEALTHY, 1L, 0L, 0L);
        PipelineBackpressureSnapshot.ExecutorSnapshot executor = new PipelineBackpressureSnapshot.ExecutorSnapshot(
                "executor", PipelineStatus.HEALTHY, 1, 1, 0, 0, 100, 0D, 0L, 0L);
        return new PipelineBackpressureSnapshot(PipelineStatus.HEALTHY, 1788998400000L,
                ingress, stream, history, cloud,
                Map.of("cache", executor, "stream", executor, "streamWriter", executor,
                        "history", executor, "report", executor),
                List.of("STATIC_RISK"));
    }
}
