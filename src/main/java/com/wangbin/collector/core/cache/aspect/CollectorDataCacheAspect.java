package com.wangbin.collector.core.cache.aspect;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.base.BaseCollector;
import com.wangbin.collector.core.processor.ProcessResult;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.beans.factory.annotation.Autowired;
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
    private CollectorDataPostProcessor dataPostProcessor;

    @Pointcut("execution(* com.wangbin.collector.core.collector.protocol.base.ReadableCollector.readPoint(..))")
    public void readPointPointcut() {
    }

    @Pointcut("execution(* com.wangbin.collector.core.collector.protocol.base.ReadableCollector.readPoints(..))")
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

            dataPostProcessor.savePointAsync(deviceId, point, cacheValue);
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

            dataPostProcessor.saveBatchAsync(deviceId, points, result, collector);
        } catch (Exception e) {
            log.error("prepare async batch cache failed", e);
        }
    }
}
