package com.wangbin.collector.core.cloud.aggregation;

import com.wangbin.collector.common.domain.cloud.CloudDeviceIdentity;
import com.wangbin.collector.core.report.model.ReportData;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 纵向点位数据转横向云端属性快照。
 */
@Service
public class CloudAggregationService {

    public CloudAggregateSnapshot snapshotOf(ReportData data) {
        if (data == null) {
            return null;
        }
        String productKey = Optional.ofNullable(data.getMetadata().get("productKey"))
                .map(String::valueOf)
                .orElse("");
        String aggregateTargetId = Optional.ofNullable(data.getMetadata().get("shadowKey"))
                .map(String::valueOf)
                .orElseGet(() -> Optional.ofNullable(data.getMetadata().get("aggregateTargetId"))
                        .map(String::valueOf)
                        .orElse(data.getDeviceId()));
        return new CloudAggregateSnapshot(
                aggregateTargetId,
                CloudDeviceIdentity.of(productKey, data.getDeviceId()),
                data.getProperties(),
                data.getPropertyTs(),
                data.getPropertyQuality(),
                data.getPropertyMetadata(),
                data.getEvents());
    }
}
