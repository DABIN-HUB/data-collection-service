package com.wangbin.collector.core.cache.aspect;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.scheduler.CollectionTaskGuard;
import com.wangbin.collector.core.processor.ProcessResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 遥测后处理的异步入口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CollectorDataPostProcessor {

    @Qualifier("cacheAsyncExecutor")
    private final Executor cacheAsyncExecutor;
    private final TelemetryPostProcessPipeline pipeline;
    private final CollectionTaskGuard collectionTaskGuard;

    /**
     * 写入或持久化业务数据。
     */
    public void savePointAsync(String deviceId, DataPoint point, Object value) {
        Long generation = captureGeneration(deviceId);
        submit(deviceId, point, generation, () -> {
            ProcessResult processResult = toProcessResult(value);
            if (processResult == null) {
                return;
            }
            if (!shouldProcess(deviceId, generation)) {
                return;
            }
            pipeline.process(new TelemetryPostProcessContext(
                    deviceId,
                    point,
                    processResult,
                    value,
                    System.currentTimeMillis(),
                    generation
            ));
        });
    }

    /**
     * 写入或持久化业务数据。
     */
    public void saveBatchAsync(String deviceId,
                               List<DataPoint> points,
                               Map<String, Object> values,
                               Map<String, ProcessResult> processResults) {
        Long generation = captureGeneration(deviceId);
        submit(deviceId, null, generation, () -> {
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
                if (point == null) {
                    continue;
                }
                String pointId = point.getPointId();
                Object value = values.get(pointId);
                if (value == null) {
                    continue;
                }
                ProcessResult collectorResult = processResults != null
                        ? processResults.get(pointId) : null;
                Object cacheValue = collectorResult != null ? collectorResult : value;
                ProcessResult processResult = toProcessResult(cacheValue);
                if (processResult == null) {
                    continue;
                }
                pipeline.process(new TelemetryPostProcessContext(
                        deviceId,
                        point,
                        processResult,
                        cacheValue,
                        System.currentTimeMillis(),
                        generation
                ));
            }
            log.debug("异步批量后处理成功, 设备={}, 点位={}", deviceId, points.size());
        });
    }

    /**
     * 解析或转换业务数据。
     */
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

    /**
     * 处理当前业务流程。
     */
    private void submit(String deviceId, DataPoint point, Long generation, Runnable task) {
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
                    log.error("异步遥测后处理失败, 设备={}, 点位={}",
                            deviceId,
                            point != null ? point.getPointId() : "batch",
                            e);
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("遥测后处理任务被拒绝, 设备={}, 点位={}, 原因={}",
                    deviceId,
                    point != null ? point.getPointId() : "batch",
                    e.getMessage());
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean shouldProcess(String deviceId, Long generation) {
        if (generation == null) {
            return true;
        }
        return collectionTaskGuard.isCurrent(deviceId, generation);
    }

    /**
     * 执行当前业务逻辑。
     */
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
}

