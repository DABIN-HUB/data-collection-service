package com.wangbin.collector.core.collector.ingress;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.cache.aspect.CollectorDataPostProcessor;
import com.wangbin.collector.core.processor.ProcessResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Unified ingress for telemetry produced by protocol callbacks.
 */
@Slf4j
@Service
public class TelemetryIngressService {

    @Autowired
    private CollectorDataPostProcessor dataPostProcessor;

    public void append(String deviceId, DataPoint point, ProcessResult processResult) {
        if (deviceId == null || deviceId.isBlank() || point == null || processResult == null) {
            return;
        }
        try {
            dataPostProcessor.savePointAsync(deviceId, point, processResult);
        } catch (Exception e) {
            log.error("telemetry ingress append failed, device={}, point={}",
                    deviceId, point.getPointId(), e);
        }
    }
}
