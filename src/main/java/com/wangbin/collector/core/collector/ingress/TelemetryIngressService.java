package com.wangbin.collector.core.collector.ingress;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.cache.aspect.CollectorDataPostProcessor;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.core.processor.ProcessContext;
import com.wangbin.collector.core.processor.DataQualityProcessor;
import com.wangbin.collector.core.processor.ProcessResultMetadataKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Unified ingress for 遥测 produced by 协议 callbacks.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelemetryIngressService {

    private final CollectorDataPostProcessor dataPostProcessor;
    private final DataQualityProcessor dataQualityProcessor;

    /**
     * 写入或持久化业务数据。
     */
    public void append(String deviceId, DataPoint point, ProcessResult processResult) {
        if (deviceId == null || deviceId.isBlank() || point == null || processResult == null) {
            return;
        }
        try {
            dataPostProcessor.savePointAsync(deviceId, point, processResult);
        } catch (Exception e) {
            log.error("遥测 ingress 追加 失败, 设备={}, 点位={}",
                    deviceId, point.getPointId(), e);
        }
    }

    /**
     * 写入或持久化业务数据。
     */
    public ProcessResult appendRaw(String deviceId,
                                   DataPoint point,
                                   Object rawValue,
                                   Integer sourceQuality,
                                   Long collectTime,
                                   String source) {
        if (deviceId == null || deviceId.isBlank() || point == null) {
            throw new IllegalArgumentException("设备标识和点位不能为空");
        }
        long resolvedCollectTime = collectTime != null && collectTime > 0
                ? collectTime : System.currentTimeMillis();
        Object processedValue = applyLinearTransform(point, rawValue);
        ProcessContext context = new ProcessContext();
        context.setCollectTime(resolvedCollectTime);
        context.setRawQuality(sourceQuality != null ? sourceQuality : 100);
        context.addAttribute("deviceId", deviceId);
        ProcessResult result = dataQualityProcessor.process(context, point, processedValue);
        if (sourceQuality != null) {
            result.setQuality(Math.min(result.getQuality(), Math.max(0, Math.min(100, sourceQuality))));
        }
        result.addMetadata(ProcessResultMetadataKeys.RAW_VALUE, rawValue);
        result.addMetadata(ProcessResultMetadataKeys.PROCESSED_VALUE, processedValue);
        result.addMetadata(ProcessResultMetadataKeys.COLLECT_TIME, resolvedCollectTime);
        result.addMetadata(ProcessResultMetadataKeys.SOURCE,
                source == null || source.isBlank() ? "EDGE_GATEWAY" : source);
        append(deviceId, point, result);
        return result;
    }

    /**
     * 处理当前业务流程。
     */
    private Object applyLinearTransform(DataPoint point, Object rawValue) {
        if (!(rawValue instanceof Number number)) {
            return rawValue;
        }
        double factor = point.getScalingFactor() != null ? point.getScalingFactor() : 1.0d;
        double offset = point.getOffset() != null ? point.getOffset() : 0.0d;
        if (Double.compare(factor, 1.0d) == 0 && Double.compare(offset, 0.0d) == 0) {
            return rawValue;
        }
        return number.doubleValue() * factor + offset;
    }
}
