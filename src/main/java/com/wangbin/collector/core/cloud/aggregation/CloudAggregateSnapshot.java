package com.wangbin.collector.core.cloud.aggregation;

import com.wangbin.collector.core.cloud.model.CloudDeviceIdentity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 横向属性快照，是云平台属性上报和批量上报的标准前置输入。
 */
public class CloudAggregateSnapshot {

    private final String aggregateTargetId;
    private final CloudDeviceIdentity identity;
    private final Map<String, Object> properties;
    private final Map<String, Long> propertyTs;
    private final Map<String, String> propertyQuality;
    private final Map<String, Map<String, Object>> sourceTrace;

    public CloudAggregateSnapshot(String aggregateTargetId,
                                  CloudDeviceIdentity identity,
                                  Map<String, Object> properties,
                                  Map<String, Long> propertyTs,
                                  Map<String, String> propertyQuality,
                                  Map<String, Map<String, Object>> sourceTrace) {
        this.aggregateTargetId = aggregateTargetId;
        this.identity = identity;
        this.properties = new LinkedHashMap<>(properties);
        this.propertyTs = new LinkedHashMap<>(propertyTs);
        this.propertyQuality = new LinkedHashMap<>(propertyQuality);
        this.sourceTrace = new LinkedHashMap<>(sourceTrace);
    }

    public String aggregateTargetId() {
        return aggregateTargetId;
    }

    public CloudDeviceIdentity identity() {
        return identity;
    }

    public Map<String, Object> properties() {
        return Collections.unmodifiableMap(properties);
    }

    public Map<String, Long> propertyTs() {
        return Collections.unmodifiableMap(propertyTs);
    }

    public Map<String, String> propertyQuality() {
        return Collections.unmodifiableMap(propertyQuality);
    }

    public Map<String, Map<String, Object>> sourceTrace() {
        return Collections.unmodifiableMap(sourceTrace);
    }
}
