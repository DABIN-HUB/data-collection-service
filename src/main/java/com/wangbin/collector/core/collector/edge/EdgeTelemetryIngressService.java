package com.wangbin.collector.core.collector.edge;

import com.wangbin.collector.api.controller.dto.EdgeTelemetryBatchRequest;
import com.wangbin.collector.api.controller.dto.EdgeTelemetryItem;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.ingress.TelemetryIngressService;
import com.wangbin.collector.core.config.support.DevicePointResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 独立实时边缘进程的数据入口，负责点位映射和序号去重。
 */
@Service
@RequiredArgsConstructor
public class EdgeTelemetryIngressService {

    private final DevicePointResolver devicePointResolver;
    private final TelemetryIngressService telemetryIngressService;
    private final ConcurrentMap<String, Long> latestSequences = new ConcurrentHashMap<>();

    public EdgeTelemetryIngressResult ingest(EdgeTelemetryBatchRequest request) {
        int acceptedCount = 0;
        int duplicateCount = 0;
        List<String> errors = new ArrayList<>();
        for (EdgeTelemetryItem item : request.items()) {
            String sequenceKey = request.gatewayId() + ':' + item.deviceId();
            DataPoint point = devicePointResolver.resolve(item.deviceId(), item.pointRef()).orElse(null);
            if (point == null) {
                errors.add(item.deviceId() + '/' + item.pointRef() + "：点位不存在");
                continue;
            }
            if (!acceptSequence(sequenceKey, item.sequence())) {
                duplicateCount++;
                continue;
            }
            try {
                telemetryIngressService.appendRaw(
                        item.deviceId(), point, item.value(), item.quality(), item.timestamp(),
                        "EDGE_" + request.protocol().name());
                acceptedCount++;
            } catch (Exception ex) {
                latestSequences.remove(sequenceKey, item.sequence());
                errors.add(item.deviceId() + '/' + item.pointRef() + "：" + ex.getMessage());
            }
        }
        return new EdgeTelemetryIngressResult(
                request.gatewayId(), request.configVersion(), acceptedCount,
                duplicateCount, errors.size(), List.copyOf(errors));
    }

    private boolean acceptSequence(String key, long sequence) {
        AtomicBoolean accepted = new AtomicBoolean(false);
        latestSequences.compute(key, (ignored, current) -> {
            if (current == null || sequence > current) {
                accepted.set(true);
                return sequence;
            }
            return current;
        });
        return accepted.get();
    }
}
