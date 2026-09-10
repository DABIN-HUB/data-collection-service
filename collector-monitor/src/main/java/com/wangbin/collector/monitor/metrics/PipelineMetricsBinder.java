package com.wangbin.collector.monitor.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * 小而稳定的 pipeline alert-grade Micrometer 指标绑定器。
 */
@Component
public class PipelineMetricsBinder implements MeterBinder {

    public static final String METRIC_PREFIX = "collector.pipeline.";
    private static final String[] STATUSES = {"HEALTHY", "WARNING", "DANGER", "UNKNOWN", "DISABLED"};

    private final PipelineBackpressureMonitorService monitorService;

    /**
     * 创建当前组件实例。
     */
    public PipelineMetricsBinder(PipelineBackpressureMonitorService monitorService) {
        this.monitorService = monitorService;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        bindStage(registry, "ingress", snapshot -> snapshot.ingress().enabled(),
                snapshot -> snapshot.ingress().status(),
                snapshot -> snapshot.ingress().localPending(), snapshot -> snapshot.ingress().localCapacity(),
                snapshot -> snapshot.ingress().localUtilization(),
                snapshot -> snapshot.ingress().redisPending(), snapshot -> snapshot.ingress().rejectedItems(),
                snapshot -> snapshot.ingress().droppedItems(), snapshot -> snapshot.ingress().redisDeadLetter(),
                snapshot -> 0D);
        bindStage(registry, "stream", snapshot -> snapshot.stream().enabled(),
                snapshot -> snapshot.stream().status(),
                snapshot -> snapshot.stream().bufferSize(), snapshot -> snapshot.stream().bufferCapacity(),
                snapshot -> snapshot.stream().bufferUtilization(),
                snapshot -> snapshot.stream().bufferSize(), snapshot -> snapshot.stream().admissionRejected(),
                snapshot -> snapshot.stream().admissionDropped() + snapshot.stream().shutdownDroppedRows(),
                snapshot -> snapshot.stream().redisXaddFailures() + snapshot.stream().writerLoopFailures(),
                snapshot -> 0D);
        bindStage(registry, "history", snapshot -> snapshot.history().enabled(),
                snapshot -> snapshot.history().status(),
                snapshot -> snapshot.history().localPending(), snapshot -> snapshot.history().localCapacity(),
                snapshot -> snapshot.history().localUtilization(),
                snapshot -> snapshot.history().redisPending(),
                snapshot -> snapshot.history().rejectedRedisBuffered() + snapshot.history().rejectedLocalBuffered(),
                snapshot -> snapshot.history().writeFailureDropped() + snapshot.history().rejectedDropped()
                        + snapshot.history().batchFallbackDroppedRows(),
                snapshot -> snapshot.history().redisDeadLetter() + snapshot.history().replayFailedRows(),
                snapshot -> snapshot.history().liveFlushQueueUtilization());
        bindCloud(registry);
        bindExecutor(registry, "cache_executor", "cache");
        bindExecutor(registry, "stream_executor", "stream");
        bindExecutor(registry, "stream_writer_executor", "streamWriter");
        bindExecutor(registry, "history_executor", "history");
        bindExecutor(registry, "report_executor", "report");
    }

    private void bindStage(MeterRegistry registry,
                           String stage,
                           Function<PipelineBackpressureSnapshot, Boolean> enabled,
                           Function<PipelineBackpressureSnapshot, PipelineStatus> status,
                           ToDoubleFunction<PipelineBackpressureSnapshot> queueSize,
                           ToDoubleFunction<PipelineBackpressureSnapshot> queueCapacity,
                           ToDoubleFunction<PipelineBackpressureSnapshot> queueUtilization,
                           ToDoubleFunction<PipelineBackpressureSnapshot> backlog,
                           ToDoubleFunction<PipelineBackpressureSnapshot> rejected,
                           ToDoubleFunction<PipelineBackpressureSnapshot> dropped,
                           ToDoubleFunction<PipelineBackpressureSnapshot> failures,
                           ToDoubleFunction<PipelineBackpressureSnapshot> livePressure) {
        gauge(registry, "enabled", stage, "state", snapshot -> enabled.apply(snapshot) ? 1D : 0D);
        for (String state : STATUSES) {
            gauge(registry, "status", stage, state,
                    snapshot -> status.apply(snapshot).name().equals(state) ? 1D : 0D);
        }
        gauge(registry, "queue.size", stage, "local", queueSize);
        gauge(registry, "queue.capacity", stage, "local", queueCapacity);
        gauge(registry, "queue.utilization", stage, "local", queueUtilization);
        gauge(registry, "backlog", stage, "current", backlog);
        gauge(registry, "rejected", stage, "total", rejected);
        gauge(registry, "dropped", stage, "total", dropped);
        gauge(registry, "failures", stage, "total", failures);
        if ("history".equals(stage)) {
            gauge(registry, "queue.utilization", stage, "live_flush", livePressure);
        }
    }

    private void bindCloud(MeterRegistry registry) {
        gauge(registry, "enabled", "cloud", "state", snapshot -> snapshot.cloud().enabled() ? 1D : 0D);
        for (String state : STATUSES) {
            gauge(registry, "status", "cloud", state,
                    snapshot -> snapshot.cloud().status().name().equals(state) ? 1D : 0D);
        }
        gauge(registry, "backlog", "cloud", "outbox_pending", snapshot -> snapshot.cloud().pending());
        gauge(registry, "backlog", "cloud", "outbox_isolated", snapshot -> snapshot.cloud().isolated());
        gauge(registry, "oldest.age", "cloud", "outbox", snapshot -> snapshot.cloud().oldestMessageAgeMillis());
    }

    private void bindExecutor(MeterRegistry registry, String stage, String key) {
        gauge(registry, "enabled", stage, "state", snapshot -> executor(snapshot, key).status() == PipelineStatus.UNKNOWN ? 0D : 1D);
        for (String state : STATUSES) {
            gauge(registry, "status", stage, state,
                    snapshot -> executor(snapshot, key).status().name().equals(state) ? 1D : 0D);
        }
        gauge(registry, "queue.size", stage, "executor", snapshot -> executor(snapshot, key).queueSize());
        gauge(registry, "queue.capacity", stage, "executor", snapshot -> executor(snapshot, key).queueCapacity());
        gauge(registry, "queue.utilization", stage, "executor", snapshot -> executor(snapshot, key).queueUtilization());
        gauge(registry, "rejected", stage, "executor", snapshot -> executor(snapshot, key).rejectedCount());
    }

    private PipelineBackpressureSnapshot.ExecutorSnapshot executor(PipelineBackpressureSnapshot snapshot, String key) {
        Map<String, PipelineBackpressureSnapshot.ExecutorSnapshot> executors = snapshot.executors();
        PipelineBackpressureSnapshot.ExecutorSnapshot executor = executors == null ? null : executors.get(key);
        return executor == null ? new PipelineBackpressureSnapshot.ExecutorSnapshot(
                key, PipelineStatus.UNKNOWN, -1, -1, -1, -1, -1, -1D, -1L, -1L) : executor;
    }

    private void gauge(MeterRegistry registry,
                       String metric,
                       String stage,
                       String type,
                       ToDoubleFunction<PipelineBackpressureSnapshot> value) {
        Gauge.builder(METRIC_PREFIX + metric, monitorService, service -> value.applyAsDouble(service.getSnapshot()))
                .tag("stage", stage)
                .tag("type", type)
                .register(registry);
    }
}
