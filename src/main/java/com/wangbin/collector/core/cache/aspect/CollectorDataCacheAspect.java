package com.wangbin.collector.core.cache.aspect;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.base.BaseCollector;
import com.wangbin.collector.core.processor.ProcessResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 捕获采集器读取结果，并异步委托遥测后处理链路执行。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class CollectorDataCacheAspect {

    private final CollectorDataPostProcessor dataPostProcessor;

    /**
     * 查询并返回业务数据。
     */
    @Pointcut("execution(* com.wangbin.collector.core.collector.protocol.base.ReadableCollector.readPoint(..))")
    public void readPointPointcut() {
    }

    /**
     * 查询并返回业务数据。
     */
    @Pointcut("execution(* com.wangbin.collector.core.collector.protocol.base.ReadableCollector.readPoints(..))")
    public void readPointsPointcut() {
    }

    /**
     * 执行当前业务逻辑。
     */
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
            Map<String, ProcessResult> invocationResults = collector != null
                    ? collector.takeInvocationProcessResults() : Map.of();
            ProcessResult processResult = invocationResults.get(point.getPointId());
            Object cacheValue = processResult != null ? processResult : result;

            dataPostProcessor.savePointAsync(deviceId, point, cacheValue);
        } catch (Exception e) {
            log.error("准备异步缓存失败", e);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
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
            Map<String, ProcessResult> invocationResults = collector != null
                    ? collector.takeInvocationProcessResults() : Map.of();

            dataPostProcessor.saveBatchAsync(deviceId, points, result, invocationResults);
        } catch (Exception e) {
            log.error("准备异步批量缓存失败", e);
        }
    }
}
