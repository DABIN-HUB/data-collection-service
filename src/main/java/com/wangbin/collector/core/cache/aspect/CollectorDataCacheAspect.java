package com.wangbin.collector.core.cache.aspect;

import com.wangbin.collector.common.constant.MessageConstant;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.cache.manager.MultiLevelCacheManager;
import com.wangbin.collector.core.cache.model.CacheKey;
import com.wangbin.collector.core.cache.service.TelemetryStreamService;
import com.wangbin.collector.core.collector.protocol.base.BaseCollector;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.core.report.service.CacheReportService;
import com.wangbin.collector.storage.service.HistoryDataService;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 采集数据缓存切面。
 * 在原有缓存和上报链路上，新增一条 Redis Stream 实时写入分支。
 */
@Slf4j
@Aspect
@Component
public class CollectorDataCacheAspect {

    @Autowired
    private MultiLevelCacheManager multiLevelCacheManager;

    @Autowired
    private CacheReportService cacheReportService;

    @Autowired
    private TelemetryStreamService telemetryStreamService;
    
    @Autowired(required = false)
    private HistoryDataService historyDataService;

    @Pointcut("execution(* com.wangbin.collector.core.collector.protocol.base.ProtocolCollector.readPoint(..))")
    public void readPointPointcut() {
    }

    @Pointcut("execution(* com.wangbin.collector.core.collector.protocol.base.ProtocolCollector.readPoints(..))")
    public void readPointsPointcut() {
    }

    @AfterReturning(pointcut = "readPointPointcut()", returning = "result")
    public void afterReadPoint(JoinPoint joinPoint, Object result) {
        try {
            if (result == null) {
                return;
            }

            DataPoint point = (DataPoint) joinPoint.getArgs()[0];
            if (point == null) {
                return;
            }

            String deviceId = point.getDeviceId();
            if (deviceId == null || deviceId.isEmpty()) {
                return;
            }

            BaseCollector collector = joinPoint.getTarget() instanceof BaseCollector
                    ? (BaseCollector) joinPoint.getTarget() : null;
            ProcessResult processResult = collector != null
                    ? collector.getLatestProcessResult(point.getPointId()) : null;
            Object cacheValue = processResult != null ? processResult : result;

            asyncSaveToCache(deviceId, point, cacheValue);
        } catch (Exception e) {
            log.error("prepare async cache failed", e);
        }
    }

    @AfterReturning(pointcut = "readPointsPointcut()", returning = "result")
    public void afterReadPoints(JoinPoint joinPoint, Map<String, Object> result) {
        try {
            if (result == null || result.isEmpty()) {
                return;
            }

            List<DataPoint> points = (List<DataPoint>) joinPoint.getArgs()[0];
            if (points == null || points.isEmpty()) {
                return;
            }

            String deviceId = points.stream()
                    .filter(point -> point != null && point.getDeviceId() != null)
                    .map(DataPoint::getDeviceId)
                    .findFirst()
                    .orElse(null);
            if (deviceId == null) {
                return;
            }

            BaseCollector collector = joinPoint.getTarget() instanceof BaseCollector
                    ? (BaseCollector) joinPoint.getTarget() : null;

            asyncBatchSaveToCache(deviceId, points, result, collector);
        } catch (Exception e) {
            log.error("prepare async batch cache failed", e);
        }
    }

    @Async("cacheAsyncExecutor")
    protected void asyncSaveToCache(String deviceId, DataPoint point, Object value) {
        try {
            if (!shouldCache(point) || value == null) {
                log.debug("skip cache for {}.{}", deviceId, point.getPointName());
                return;
            }

            CacheKey cacheKey = CacheKey.dataKey(deviceId, point.getPointId());
            long expireTime = getCacheExpireTime(point);
            multiLevelCacheManager.put(cacheKey, value, expireTime);

            ProcessResult processResult = toProcessResult(value);
            telemetryStreamService.append(deviceId, point, processResult);
            if (historyDataService != null) {
                historyDataService.savePoint(deviceId, point, processResult);
            }
            cacheReportService.reportPoint(deviceId, MessageConstant.MESSAGE_TYPE_PROPERTY_POST, point, value);
        } catch (Exception e) {
            log.error("async cache failed", e);
        }
    }

    @Async("cacheAsyncExecutor")
    protected void asyncBatchSaveToCache(String deviceId, List<DataPoint> points, Map<String, Object> values,
                                         BaseCollector collector) {
        try {
            for (DataPoint point : points) {
                String pointId = point.getPointId();
                Object value = values.get(pointId);

                if (value != null && shouldCache(point)) {
                    ProcessResult processResult = collector != null
                            ? collector.getLatestProcessResult(pointId) : null;
                    Object cacheValue = processResult != null ? processResult : value;
                    CacheKey cacheKey = CacheKey.dataKey(deviceId, pointId);
                    long expireTime = getCacheExpireTime(point);
                    multiLevelCacheManager.put(cacheKey, cacheValue, expireTime);

                    ProcessResult normalized = toProcessResult(cacheValue);
                    telemetryStreamService.append(deviceId, point, normalized);
                    if (historyDataService != null) {
                        historyDataService.savePoint(deviceId, point, normalized);
                    }
                    cacheReportService.reportPoint(deviceId, MessageConstant.MESSAGE_TYPE_PROPERTY_POST, point, processResult);
                }
            }

            log.debug("async batch cache success, device={}, points={}", deviceId, points.size());
        } catch (Exception e) {
            log.error("async batch cache failed", e);
        }
    }

    private boolean shouldCache(DataPoint point) {
        return point != null && point.needCache();
    }

    private long getCacheExpireTime(DataPoint point) {
        if (point.getCacheDuration() != null && point.getCacheDuration() > 0) {
            return point.getCacheDuration() * 1000L;
        }

        if (point.getPriority() != null) {
            if (point.getPriority() <= 3) {
                return 7200_000L;
            } else if (point.getPriority() <= 7) {
                return 3600_000L;
            }
        }

        return 1800_000L;
    }

    private ProcessResult toProcessResult(Object value) {
        if (value instanceof ProcessResult processResult) {
            return processResult;
        }
        ProcessResult fallback = new ProcessResult();
        fallback.setSuccess(true);
        fallback.setRawValue(value);
        fallback.setProcessedValue(value);
        fallback.setMessage("fallback process result for stream");
        return fallback;
    }
}
