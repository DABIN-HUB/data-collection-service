package com.wangbin.collector.core.cloud.aggregation;

import com.wangbin.collector.core.cloud.model.CloudDeviceIdentity;

/**
 * 云端聚合目标，表示一组本地点位最终汇聚到哪个云端设备。
 */
public record CloudAggregateTarget(String aggregateTargetId,
                                   CloudDeviceIdentity cloudIdentity,
                                   CloudAggregationPolicy policy) {

    public String key() {
        if (aggregateTargetId != null && !aggregateTargetId.isBlank()) {
            return aggregateTargetId;
        }
        return cloudIdentity != null ? cloudIdentity.key() : "";
    }
}
