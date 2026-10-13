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
            Map<String, ProcessResult> invocationResults = takeInvocationResults(joinPoint);
            DataPoint point = (DataPoint) joinPoint.getArgs()[0];
            if (point == null) {
                return;
            }

            String deviceId = point.getDeviceId();
            if (deviceId == null || deviceId.isEmpty()) {
                return;
            }

            ProcessResult processResult = invocationResults.get(point.getPointId());
            if (result == null && processResult == null) {
                return;
            }
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
            Map<String, ProcessResult> invocationResults = takeInvocationResults(joinPoint);
            if (result == null || result.isEmpty()) {
                return;
            }

            List<DataPoint> points = (List<DataPoint>) joinPoint.getArgs()[0];
            if (points == null || points.isEmpty()) {
                return;
            }

            // 批读以采集器所属设备为准，不能被参数中的首个外设备点位改变归属。
            String deviceId = joinPoint.getTarget() instanceof BaseCollector collector
                    && collector.getDeviceInfo() != null ? collector.getDeviceInfo().getDeviceId() : points.stream()
                    .filter(point -> point != null && point.getDeviceId() != null)
                    .map(DataPoint::getDeviceId)
                    .findFirst()
                    .orElse(null);
            if (deviceId == null) {
                return;
            }

            dataPostProcessor.saveBatchAsync(deviceId, points, result, invocationResults);
        } catch (Exception e) {
            log.error("准备异步批量缓存失败", e);
        }
    }

    /** 即使读取无值或参数无效，也必须消费并释放当前调用快照。 */
    private Map<String, ProcessResult> takeInvocationResults(JoinPoint joinPoint) {
        return joinPoint.getTarget() instanceof InvocationProcessResultSource source
                ? source.takeInvocationProcessResults() : Map.of();
    }
}
