package com.wangbin.collector.monitor.metrics;

import com.wangbin.collector.core.cache.config.TelemetryExecutorNames;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 遥测 pipeline backpressure 统一监控服务。
 */
@Slf4j
@Service
public class PipelineBackpressureMonitorService {

    public static final Duration DEFAULT_CACHE_TTL = Duration.ofSeconds(5);
    private static final double WARNING_THRESHOLD = 0.70D;
    private static final double DANGER_THRESHOLD = 0.90D;

    private final ObjectProvider<TelemetryIngressBuffer> ingressBufferProvider;
    private final ObjectProvider<StreamWriteBuffer> streamWriteBufferProvider;
    private final ObjectProvider<HistoryWriteBuffer> historyWriteBufferProvider;
    private final ObjectProvider<CloudOutboxService> cloudOutboxServiceProvider;
    private final SystemResourceMonitorService systemResourceMonitorService;
    private final TelemetryIngressBufferProperties ingressProperties;
    private final TelemetryStreamProperties streamProperties;
    private final HistoryBufferProperties historyProperties;
    private final TdengineProperties tdengineProperties;
    private final ReportProperties reportProperties;
    private final Clock clock;
    private final Duration cacheTtl;
    private final AtomicReference<PipelineBackpressureSnapshot> cachedSnapshot = new AtomicReference<>();

    /**
     * 创建当前组件实例。
     */
    @Autowired
    public PipelineBackpressureMonitorService(ObjectProvider<TelemetryIngressBuffer> ingressBufferProvider,
                                              ObjectProvider<StreamWriteBuffer> streamWriteBufferProvider,
                                              ObjectProvider<HistoryWriteBuffer> historyWriteBufferProvider,
                                              ObjectProvider<CloudOutboxService> cloudOutboxServiceProvider,
                                              SystemResourceMonitorService systemResourceMonitorService,
                                              TelemetryIngressBufferProperties ingressProperties,
                                              TelemetryStreamProperties streamProperties,
                                              HistoryBufferProperties historyProperties,
                                              TdengineProperties tdengineProperties,
                                              ReportProperties reportProperties) {
        this(ingressBufferProvider, streamWriteBufferProvider, historyWriteBufferProvider, cloudOutboxServiceProvider,
                systemResourceMonitorService, ingressProperties, streamProperties, historyProperties, tdengineProperties,
                reportProperties, Clock.systemUTC(), DEFAULT_CACHE_TTL);
    }

    PipelineBackpressureMonitorService(ObjectProvider<TelemetryIngressBuffer> ingressBufferProvider,
                                       ObjectProvider<StreamWriteBuffer> streamWriteBufferProvider,
                                       ObjectProvider<HistoryWriteBuffer> historyWriteBufferProvider,
                                       ObjectProvider<CloudOutboxService> cloudOutboxServiceProvider,
                                       SystemResourceMonitorService systemResourceMonitorService,
                                       TelemetryIngressBufferProperties ingressProperties,
                                       TelemetryStreamProperties streamProperties,
                                       HistoryBufferProperties historyProperties,
                                       TdengineProperties tdengineProperties,
                                       ReportProperties reportProperties,
                                       Clock clock,
                                       Duration cacheTtl) {
        this.ingressBufferProvider = ingressBufferProvider;
        this.streamWriteBufferProvider = streamWriteBufferProvider;
        this.historyWriteBufferProvider = historyWriteBufferProvider;
        this.cloudOutboxServiceProvider = cloudOutboxServiceProvider;
        this.systemResourceMonitorService = systemResourceMonitorService;
        this.ingressProperties = ingressProperties;
        this.streamProperties = streamProperties;
        this.historyProperties = historyProperties;
        this.tdengineProperties = tdengineProperties;
        this.reportProperties = reportProperties;
        this.clock = clock;
        this.cacheTtl = cacheTtl == null ? DEFAULT_CACHE_TTL : cacheTtl;
    }

    public PipelineBackpressureSnapshot getSnapshot() {
        PipelineBackpressureSnapshot snapshot = cachedSnapshot.get();
        Instant now = Instant.now(clock);
        if (snapshot != null && !Instant.ofEpochMilli(snapshot.generatedAt()).plus(cacheTtl).isBefore(now)) {
            return snapshot;
        }
        synchronized (this) {
            snapshot = cachedSnapshot.get();
            now = Instant.now(clock);
            if (snapshot != null && !Instant.ofEpochMilli(snapshot.generatedAt()).plus(cacheTtl).isBefore(now)) {
                return snapshot;
            }
            PipelineBackpressureSnapshot refreshed = refresh(now);
            cachedSnapshot.set(refreshed);
            return refreshed;
        }
    }

    private PipelineBackpressureSnapshot refresh(Instant now) {
        List<String> risks = new ArrayList<>();
        PipelineBackpressureSnapshot.IngressSnapshot ingress = collectIngress(risks);
        PipelineBackpressureSnapshot.StreamSnapshot stream = collectStream(risks);
        PipelineBackpressureSnapshot.HistorySnapshot history = collectHistory(risks);
        PipelineBackpressureSnapshot.CloudSnapshot cloud = collectCloud(risks);
        Map<String, PipelineBackpressureSnapshot.ExecutorSnapshot> executors = collectExecutors(risks);
        PipelineStatus status = overall(List.of(
                ingress.status(), stream.status(), history.status(), cloud.status()), executors.values());
        return new PipelineBackpressureSnapshot(status, now.toEpochMilli(), ingress, stream, history, cloud,
                Map.copyOf(executors), List.copyOf(risks));
    }

    private PipelineBackpressureSnapshot.IngressSnapshot collectIngress(List<String> risks) {
        if (!ingressProperties.isEnabled()) {
            return new PipelineBackpressureSnapshot.IngressSnapshot(false, PipelineStatus.DISABLED,
                    0L, 0L, 0L, 0, ingressProperties.getLocalQueueCapacity(), 0D,
                    0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
        }
        try {
            TelemetryIngressBuffer buffer = ingressBufferProvider.getIfAvailable();
            if (buffer == null) {
                risks.add("INGRESS_UNAVAILABLE");
                return unknownIngress();
            }
            TelemetryIngressBufferMetrics metrics = buffer.metrics();
            double utilization = utilization(metrics.localPending(), metrics.localCapacity());
            PipelineStatus status = queueStatus(utilization);
            if (metrics.redisPending() < 0 || metrics.redisProcessing() < 0 || metrics.redisDeadLetter() < 0) {
                status = PipelineStatus.UNKNOWN;
                risks.add("INGRESS_REDIS_UNAVAILABLE");
            }
            if (metrics.redisDeadLetter() > 0) {
                status = PipelineStatus.DANGER;
                risks.add("INGRESS_DEAD_LETTER");
            }
            addQueueRisk(risks, "INGRESS_LOCAL_QUEUE_HIGH", utilization);
            return new PipelineBackpressureSnapshot.IngressSnapshot(true, status,
                    metrics.redisPending(), metrics.redisProcessing(), metrics.redisDeadLetter(),
                    metrics.localPending(), metrics.localCapacity(), utilization,
                    metrics.rejectedTasks(), metrics.rejectedItems(), metrics.redisBufferedItems(),
                    metrics.localBufferedItems(), metrics.droppedItems(), metrics.replayCompletedItems(),
                    metrics.pendingRemoveFailures(), metrics.poisonDeadLetterItems(),
                    metrics.staleSameRuntimeDroppedItems(), metrics.crossRuntimeRecoveredItems(),
                    metrics.legacyEnvelopeRecoveredItems());
        } catch (RuntimeException exception) {
            log.warn("读取入口缓冲监控快照失败", exception);
            risks.add("INGRESS_SOURCE_FAILURE");
            return unknownIngress();
        }
    }

    private PipelineBackpressureSnapshot.StreamSnapshot collectStream(List<String> risks) {
        if (!streamProperties.isEnabled()) {
            return new PipelineBackpressureSnapshot.StreamSnapshot(false, PipelineStatus.DISABLED,
                    0, 0, streamProperties.getBuffer().getCapacity(), 0D,
                    0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
        }
        try {
            StreamWriteBuffer buffer = streamWriteBufferProvider.getIfAvailable();
            if (buffer == null) {
                risks.add("STREAM_UNAVAILABLE");
                return unknownStream();
            }
            StreamWriteBufferMetrics metrics = buffer.metrics();
            double utilization = utilization(metrics.bufferSize(), metrics.bufferCapacity());
            PipelineStatus status = queueStatus(utilization);
            addQueueRisk(risks, "STREAM_BUFFER_HIGH", utilization);
            return new PipelineBackpressureSnapshot.StreamSnapshot(true, status,
                    metrics.bufferSize(), metrics.bufferPeak(), metrics.bufferCapacity(), utilization,
                    metrics.admissionAccepted(), metrics.admissionRejected(), metrics.admissionDropped(),
                    metrics.writerBatchCount(), metrics.writerRows(), metrics.redisPipelineCalls(),
                    metrics.redisXaddRows(), metrics.redisXaddFailures(), metrics.shutdownDroppedRows(),
                    metrics.writerLoopFailures());
        } catch (RuntimeException exception) {
            log.warn("读取 Stream 写缓冲监控快照失败", exception);
            risks.add("STREAM_SOURCE_FAILURE");
            return unknownStream();
        }
    }

    private PipelineBackpressureSnapshot.HistorySnapshot collectHistory(List<String> risks) {
        if (!tdengineProperties.isEnabled() || !historyProperties.isEnabled()) {
            return new PipelineBackpressureSnapshot.HistorySnapshot(false, PipelineStatus.DISABLED,
                    0L, 0L, 0L, 0, historyProperties.getLocalQueueCapacity(), 0D,
                    0L, 0L, 0L, 0L, 0L, 0L, 0L, 0, 0L, 0D);
        }
        try {
            HistoryWriteBuffer buffer = historyWriteBufferProvider.getIfAvailable();
            if (buffer == null) {
                risks.add("HISTORY_UNAVAILABLE");
                return unknownHistory();
            }
            HistoryBufferMetrics metrics = buffer.metrics();
            double utilization = utilization(metrics.localPending(), metrics.localCapacity());
            PipelineStatus status = max(queueStatus(utilization), liveFlushStatus(metrics.liveFlushQueueUtilization()));
            if (metrics.redisPending() < 0 || metrics.redisProcessing() < 0 || metrics.redisDeadLetter() < 0) {
                status = PipelineStatus.UNKNOWN;
                risks.add("HISTORY_REDIS_UNAVAILABLE");
            }
            if (metrics.redisDeadLetter() > 0) {
                status = PipelineStatus.DANGER;
                risks.add("HISTORY_DEAD_LETTER");
            }
            addQueueRisk(risks, "HISTORY_LOCAL_QUEUE_HIGH", utilization);
            addQueueRisk(risks, "HISTORY_LIVE_FLUSH_HIGH", metrics.liveFlushQueueUtilization());
            return new PipelineBackpressureSnapshot.HistorySnapshot(true, status,
                    metrics.redisPending(), metrics.redisProcessing(), metrics.redisDeadLetter(),
                    metrics.localPending(), metrics.localCapacity(), utilization,
                    metrics.writeFailureRedisBuffered(), metrics.rejectedRedisBuffered(),
                    metrics.writeFailureLocalBuffered(), metrics.rejectedLocalBuffered(),
                    metrics.writeFailureDropped(), metrics.rejectedDropped(), metrics.replayFailedRows(),
                    metrics.replayProcessingRows(), metrics.batchFallbackDroppedRows(),
                    metrics.liveFlushQueueUtilization());
        } catch (RuntimeException exception) {
            log.warn("读取历史缓冲监控快照失败", exception);
            risks.add("HISTORY_SOURCE_FAILURE");
            return unknownHistory();
        }
    }

    private PipelineBackpressureSnapshot.CloudSnapshot collectCloud(List<String> risks) {
        if (!reportProperties.isEnabled()) {
            return new PipelineBackpressureSnapshot.CloudSnapshot(false, PipelineStatus.DISABLED, 0L, 0L, 0L);
        }
        try {
            CloudOutboxService service = cloudOutboxServiceProvider.getIfAvailable();
            if (service == null) {
                risks.add("CLOUD_UNAVAILABLE");
                return new PipelineBackpressureSnapshot.CloudSnapshot(true, PipelineStatus.UNKNOWN, -1L, -1L, -1L);
            }
            CloudOutboxSnapshot snapshot = service.snapshot();
            if (!snapshot.enabled()) {
                return new PipelineBackpressureSnapshot.CloudSnapshot(false, PipelineStatus.DISABLED,
                        snapshot.pending(), snapshot.isolated(), snapshot.oldestMessageAgeMillis());
            }
            PipelineStatus status = PipelineStatus.HEALTHY;
            if (snapshot.pending() < 0 || snapshot.isolated() < 0 || snapshot.oldestMessageAgeMillis() < 0) {
                status = PipelineStatus.UNKNOWN;
                risks.add("CLOUD_OUTBOX_UNAVAILABLE");
            }
            if (snapshot.isolated() > 0) {
                status = PipelineStatus.DANGER;
                risks.add("CLOUD_ISOLATED_MESSAGES");
            }
            return new PipelineBackpressureSnapshot.CloudSnapshot(true, status,
                    snapshot.pending(), snapshot.isolated(), snapshot.oldestMessageAgeMillis());
        } catch (RuntimeException exception) {
            log.warn("读取云端发件箱监控快照失败", exception);
            risks.add("CLOUD_SOURCE_FAILURE");
            return new PipelineBackpressureSnapshot.CloudSnapshot(true, PipelineStatus.UNKNOWN, -1L, -1L, -1L);
        }
    }

    private Map<String, PipelineBackpressureSnapshot.ExecutorSnapshot> collectExecutors(List<String> risks) {
        Map<String, PipelineBackpressureSnapshot.ExecutorSnapshot> result = new LinkedHashMap<>();
        try {
            Map<String, SystemResourceSnapshot.ThreadPoolSnapshot> pools = systemResourceMonitorService.getThreadPools();
            putExecutor(result, risks, "cache", TelemetryExecutorNames.CACHE_STAGE, pools);
            putExecutor(result, risks, "stream", TelemetryExecutorNames.STREAM_STAGE, pools);
            putExecutor(result, risks, "streamWriter", TelemetryExecutorNames.STREAM_WRITE, pools);
            putExecutor(result, risks, "history", TelemetryExecutorNames.HISTORY_STAGE, pools);
            putExecutor(result, risks, "report", TelemetryExecutorNames.REPORT_STAGE, pools);
            return result;
        } catch (RuntimeException exception) {
            log.warn("读取线程池资源监控快照失败", exception);
            risks.add("EXECUTOR_SOURCE_FAILURE");
            for (String key : List.of("cache", "stream", "streamWriter", "history", "report")) {
                result.put(key, unknownExecutor(key));
            }
            return result;
        }
    }

    private void putExecutor(Map<String, PipelineBackpressureSnapshot.ExecutorSnapshot> result,
                             List<String> risks,
                             String key,
                             String beanName,
                             Map<String, SystemResourceSnapshot.ThreadPoolSnapshot> pools) {
        SystemResourceSnapshot.ThreadPoolSnapshot pool = pools == null ? null : pools.get(beanName);
        if (pool == null || pool.getQueueSize() < 0) {
            result.put(key, unknownExecutor(beanName));
            risks.add("EXECUTOR_UNAVAILABLE");
            return;
        }
        PipelineStatus status = queueStatus(pool.getQueueUtilization());
        addQueueRisk(risks, "EXECUTOR_QUEUE_HIGH", pool.getQueueUtilization());
        result.put(key, new PipelineBackpressureSnapshot.ExecutorSnapshot(beanName, status,
                pool.getCorePoolSize(), pool.getMaxPoolSize(), pool.getActiveCount(),
                pool.getQueueSize(), pool.getQueueCapacity(), pool.getQueueUtilization(),
                pool.getCompletedTaskCount(), pool.getRejectedCount()));
    }

    private PipelineBackpressureSnapshot.IngressSnapshot unknownIngress() {
        return new PipelineBackpressureSnapshot.IngressSnapshot(true, PipelineStatus.UNKNOWN,
                -1L, -1L, -1L, -1, -1, -1D,
                -1L, -1L, -1L, -1L, -1L, -1L, -1L, -1L, -1L, -1L, -1L);
    }

    private PipelineBackpressureSnapshot.StreamSnapshot unknownStream() {
        return new PipelineBackpressureSnapshot.StreamSnapshot(true, PipelineStatus.UNKNOWN,
                -1, -1, -1, -1D,
                -1L, -1L, -1L, -1L, -1L, -1L, -1L, -1L, -1L, -1L);
    }

    private PipelineBackpressureSnapshot.HistorySnapshot unknownHistory() {
        return new PipelineBackpressureSnapshot.HistorySnapshot(true, PipelineStatus.UNKNOWN,
                -1L, -1L, -1L, -1, -1, -1D,
                -1L, -1L, -1L, -1L, -1L, -1L, -1L, -1, -1L, -1D);
    }

    private PipelineBackpressureSnapshot.ExecutorSnapshot unknownExecutor(String beanName) {
        return new PipelineBackpressureSnapshot.ExecutorSnapshot(beanName, PipelineStatus.UNKNOWN,
                -1, -1, -1, -1, -1, -1D, -1L, -1L);
    }

    private double utilization(int size, int capacity) {
        if (size < 0 || capacity < 0) {
            return -1D;
        }
        if (capacity == 0) {
            return 0D;
        }
        return Math.min(1D, Math.max(0D, (double) size / capacity));
    }

    private PipelineStatus queueStatus(double utilization) {
        if (utilization < 0D) {
            return PipelineStatus.UNKNOWN;
        }
        if (utilization >= DANGER_THRESHOLD) {
            return PipelineStatus.DANGER;
        }
        if (utilization >= WARNING_THRESHOLD) {
            return PipelineStatus.WARNING;
        }
        return PipelineStatus.HEALTHY;
    }

    private PipelineStatus liveFlushStatus(double utilization) {
        return queueStatus(utilization);
    }

    private void addQueueRisk(List<String> risks, String risk, double utilization) {
        if (utilization >= WARNING_THRESHOLD) {
            risks.add(risk);
        }
    }

    private PipelineStatus overall(List<PipelineStatus> stages,
                                   Iterable<PipelineBackpressureSnapshot.ExecutorSnapshot> executors) {
        PipelineStatus result = PipelineStatus.HEALTHY;
        for (PipelineStatus stage : stages) {
            result = max(result, stage);
        }
        for (PipelineBackpressureSnapshot.ExecutorSnapshot executor : executors) {
            result = max(result, executor.status());
        }
        return result == PipelineStatus.DISABLED ? PipelineStatus.HEALTHY : result;
    }

    private PipelineStatus max(PipelineStatus left, PipelineStatus right) {
        return severity(left) >= severity(right) ? left : right;
    }

    private int severity(PipelineStatus status) {
        return switch (status) {
            case DANGER -> 4;
            case WARNING -> 3;
            case UNKNOWN -> 2;
            case HEALTHY -> 1;
            case DISABLED -> 0;
        };
    }
}
