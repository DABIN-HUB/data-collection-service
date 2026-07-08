package com.wangbin.collector.core.cloud.aggregation;

import com.wangbin.collector.core.cloud.model.CloudDeviceIdentity;

/**
 * 点位到云端字段的绑定关系。
 */
public record CloudPointBinding(String aggregateTargetId,
                                CloudDeviceIdentity identity,
                                String field,
                                String messageType,
                                CloudAggregationPolicy policy) {

    public String targetKey() {
        if (aggregateTargetId != null && !aggregateTargetId.isBlank()) {
            return aggregateTargetId;
        }
        return identity != null ? identity.key() : "";
    }
}
