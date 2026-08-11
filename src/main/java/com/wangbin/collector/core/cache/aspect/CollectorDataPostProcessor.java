package com.wangbin.collector.core.cache.aspect;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.logging.RateLimitedLogReporter;
import com.wangbin.collector.core.cache.ingress.TelemetryIngressBuffer;
import com.wangbin.collector.core.cache.ingress.TelemetryIngressBufferResult;
import com.wangbin.collector.core.collector.scheduler.CollectionTaskGuard;
import com.wangbin.collector.core.processor.ProcessResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * 遥测后处理的异步入口。
 */
@Slf4j
@Component
public class CollectorDataPostProcessor {

    private static final int LATENCY_SAMPLE_LIMIT = 20_000;

    private final Executor cacheAsyncExecutor;
    private final TelemetryPostProcessPipeline pipeline;
    private final CollectionTaskGuard collectionTaskGuard;
    private final TelemetryIngressBuffer telemetryIngressBuffer;
    private final RateLimitedLogReporter entryRejectionLogReporter = new RateLimitedLogReporter(log);
    private final LongAdder batchTaskCount = new LongAdder();
    private final LongAdder batchTaskItems = new LongAdder();
    private final TelemetryLatencyReservoir batchTaskLatencyNanos = new TelemetryLatencyReservoir(LATENCY_SAMPLE_LIMIT);
    private final TelemetryLatencyReservoir batchTaskSizes = new TelemetryLatencyReservoir(LATENCY_SAMPLE_LIMIT);

    @Autowired
    public CollectorDataPostProcessor(@Qualifier("cacheAsyncExecutor") Executor cacheAsyncExecutor,
                                      TelemetryPostProcessPipeline pipeline,
                                      CollectionTaskGuard collectionTaskGuard,
                                      TelemetryIngressBuffer telemetryIngressBuffer) {
        this.cacheAsyncExecutor = cacheAsyncExecutor;
        this.pipeline = pipeline;
        this.collectionTaskGuard = collectionTaskGuard;
        this.telemetryIngressBuffer = telemetryIngressBuffer;
    }

    public CollectorDataPostProcessor(Executor cacheAsyncExecutor,
                                      TelemetryPostProcessPipeline pipeline,
                                      CollectionTaskGuard collectionTaskGuard) {
        this(cacheAsyncExecutor, pipeline, collectionTaskGuard, TelemetryIngressBuffer.noop());
    }

    /**
     * 异步写入单点遥测后处理流水线。
     */
    public void savePointAsync(String deviceId, DataPoint point, Object value) {
        Long generation = captureGeneration(deviceId);
        submit(deviceId, point, generation,
                () -> processPoint(deviceId, point, value, generation),
                () -> contextForPoint(deviceId, point, value, generation));
    }

    /**
     * 异步写入批量遥测后处理流水线。
     */
    public void saveBatchAsync(String deviceId,
                               List<DataPoint> points,
                               Map<String, Object> values,
                               Map<String, ProcessResult> processResults) {
        Long generation = captureGeneration(deviceId);
        submit(deviceId, null, generation,
                () -> processTimedBatch(deviceId, points, values, processResults, generation),
                () -> contextsForBatch(deviceId, points, values, processResults, generation));
    }

    private ProcessResult toProcessResult(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof ProcessResult processResult) {
            return processResult;
        }
        ProcessResult fallback = new ProcessResult();
        fallback.setSuccess(true);
        fallback.setRawValue(value);
        fallback.setProcessedValue(value);
        fallback.setMessage("fallback process result for telemetry pipeline");
        return fallback;
    }

    private void submit(String deviceId,
                        DataPoint point,
                        Long generation,
                        Runnable task,
                        Supplier<List<TelemetryPostProcessContext>> rejectedContextsSupplier) {
        if (deviceId == null || deviceId.isBlank() || task == null) {
            return;
        }
        try {
            cacheAsyncExecutor.execute(() -> {
                if (!shouldProcess(deviceId, generation)) {
                    return;
                }
                try {
                    task.run();
                } catch (Exception e) {
                    log.error("异步遥测后处理失败，设备={}，点位={}",
                            deviceId,
                            point != null ? point.getPointId() : "batch",
                            e);
                }
            });
        } catch (RejectedExecutionException exception) {
            handleEntryRejection(deviceId, point, rejectedContextsSupplier, exception);
        }
    }

    private void processPoint(String deviceId, DataPoint point, Object value, Long generation) {
        List<TelemetryPostProcessContext> contexts = contextForPoint(deviceId, point, value, generation);
        if (!contexts.isEmpty()) {
            pipeline.process(contexts.get(0));
        }
    }

    private void processTimedBatch(String deviceId,
                                   List<DataPoint> points,
                                   Map<String, Object> values,
                                   Map<String, ProcessResult> processResults,
                                   Long generation) {
        int batchSize = points == null ? 0 : points.size();
        long startedAt = System.nanoTime();
        try {
            processBatch(deviceId, points, values, processResults, generation);
        } finally {
            batchTaskCount.increment();
            batchTaskItems.add(batchSize);
            batchTaskSizes.add(batchSize);
            batchTaskLatencyNanos.add(System.nanoTime() - startedAt);
        }
    }

    private void processBatch(String deviceId,
                              List<DataPoint> points,
                              Map<String, Object> values,
                              Map<String, ProcessResult> processResults,
                              Long generation) {
        if (points == null || values == null || values.isEmpty()) {
            return;
        }
        if (!shouldProcess(deviceId, generation)) {
            return;
        }
        for (DataPoint point : points) {
            if (!shouldProcess(deviceId, generation)) {
                return;
            }
            TelemetryPostProcessContext context = contextForBatchPoint(deviceId, point, values, processResults, generation);
            if (context != null) {
                pipeline.process(context);
            }
        }
        log.debug("异步批量遥测后处理成功，设备={}，点位={}", deviceId, points.size());
    }

    private List<TelemetryPostProcessContext> contextForPoint(String deviceId,
                                                              DataPoint point,
                                                              Object value,
                                                              Long generation) {
        if (!shouldProcess(deviceId, generation)) {
            return Collections.emptyList();
        }
        ProcessResult processResult = toProcessResult(value);
        if (point == null || processResult == null) {
            return Collections.emptyList();
        }
        return List.of(new TelemetryPostProcessContext(
                deviceId,
                point,
                processResult,
                value,
                System.currentTimeMillis(),
                generation));
    }

    private List<TelemetryPostProcessContext> contextsForBatch(String deviceId,
                                                               List<DataPoint> points,
                                                               Map<String, Object> values,
                                                               Map<String, ProcessResult> processResults,
                                                               Long generation) {
        if (points == null || values == null || values.isEmpty() || !shouldProcess(deviceId, generation)) {
            return Collections.emptyList();
        }
        List<TelemetryPostProcessContext> contexts = new ArrayList<>(points.size());
        for (DataPoint point : points) {
            if (!shouldProcess(deviceId, generation)) {
                return contexts;
            }
            TelemetryPostProcessContext context = contextForBatchPoint(
                    deviceId, point, values, processResults, generation);
            if (context != null) {
                contexts.add(context);
            }
        }
        return contexts;
    }

    private TelemetryPostProcessContext contextForBatchPoint(String deviceId,
                                                             DataPoint point,
                                                             Map<String, Object> values,
                                                             Map<String, ProcessResult> processResults,
                                                             Long generation) {
        if (!shouldProcess(deviceId, generation)) {
            return null;
        }
        if (point == null) {
            return null;
        }
        String pointId = point.getPointId();
        Object value = values.get(pointId);
        if (value == null) {
            return null;
        }
        ProcessResult collectorResult = processResults != null ? processResults.get(pointId) : null;
        Object cacheValue = collectorResult != null ? collectorResult : value;
        ProcessResult processResult = toProcessResult(cacheValue);
        if (processResult == null) {
            return null;
        }
        return new TelemetryPostProcessContext(
                deviceId,
                point,
                processResult,
                cacheValue,
                System.currentTimeMillis(),
                generation);
    }

    private void handleEntryRejection(String deviceId,
                                      DataPoint point,
                                      Supplier<List<TelemetryPostProcessContext>> rejectedContextsSupplier,
                                      RejectedExecutionException exception) {
        List<TelemetryPostProcessContext> contexts = rejectedContextsSupplier == null
                ? Collections.emptyList() : rejectedContextsSupplier.get();
        TelemetryIngressBufferResult result;
        try {
            result = telemetryIngressBuffer.defer(contexts, exception);
        } catch (Exception fallbackException) {
            recordExplicitDropped(contexts.size(), fallbackException);
            log.error("遥测入口过载补偿失败，数据被明确丢弃，设备={}，点位={}，条数={}",
                    deviceId,
                    point != null ? point.getPointId() : "batch",
                    contexts.size(),
                    fallbackException);
            return;
        }
        if (result.inputItems() == 0) {
            entryRejectionLogReporter.warn("entry-rejected-empty",
                    "遥测后处理入口任务被拒绝，设备={}，点位={}，无可缓冲数据，原因={}",
                    deviceId, point != null ? point.getPointId() : "batch", exception.getMessage());
            return;
        }
        entryRejectionLogReporter.warn("entry-rejected-deferred",
                "遥测入口任务被拒绝，已执行入口过载处理，设备={}，点位={}，条数={}，Redis缓冲={}，本地缓冲={}，明确丢弃={}",
                deviceId,
                point != null ? point.getPointId() : "batch",
                result.inputItems(),
                result.redisBufferedItems(),
                result.localBufferedItems(),
                result.droppedItems());
    }

    private void recordExplicitDropped(int itemCount, Exception fallbackException) {
        try {
            telemetryIngressBuffer.recordDropped(itemCount, asRuntimeException(fallbackException));
        } catch (Exception metricsException) {
            fallbackException.addSuppressed(metricsException);
        }
    }

    private RuntimeException asRuntimeException(Exception exception) {
        if (exception instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new IllegalStateException(exception);
    }

    private boolean shouldProcess(String deviceId, Long generation) {
        if (generation == null) {
            return true;
        }
        return collectionTaskGuard.isCurrent(deviceId, generation);
    }

    private Long captureGeneration(String deviceId) {
        CollectionTaskGuard.CollectionTaskContext context = collectionTaskGuard.captureCurrentContext();
        if (context == null) {
            return null;
        }
        if (deviceId != null && !deviceId.equals(context.deviceId())) {
            return null;
        }
        return context.generation();
    }

    /**
     * 返回入口执行器任务热路径内部观测快照。
     */
    public CollectorDataPostProcessorMetrics metrics() {
        RateLimitedLogReporter.Snapshot logSnapshot = entryRejectionLogReporter.snapshot();
        return new CollectorDataPostProcessorMetrics(
                batchTaskCount.sum(),
                batchTaskItems.sum(),
                batchTaskSizes.percentileInt(0.50D),
                batchTaskSizes.percentileInt(0.95D),
                batchTaskSizes.maxInt(),
                batchTaskLatencyNanos.percentileMillis(0.50D),
                batchTaskLatencyNanos.percentileMillis(0.95D),
                batchTaskLatencyNanos.percentileMillis(0.99D),
                logSnapshot.emittedEvents(),
                logSnapshot.suppressedEvents());
    }

    /**
     * 重置入口任务观测采样，不影响可靠缓冲计数。
     */
    public void resetMetrics() {
        batchTaskCount.reset();
        batchTaskItems.reset();
        batchTaskLatencyNanos.reset();
        batchTaskSizes.reset();
        entryRejectionLogReporter.reset();
    }
}
