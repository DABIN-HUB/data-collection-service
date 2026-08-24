package com.wangbin.collector.core.collector.protocol.bacnet.domain;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.List;
import java.util.Map;

/**
 * 定义当前模块的业务组件。
 */
@Value
@Builder
public class BacnetCovNotification {

    boolean confirmed;
    Integer invokeId;
    int subscriberProcessIdentifier;
    int initiatingDeviceInstance;
    BacnetObjectType monitoredObjectType;
    int monitoredObjectInstance;
    Integer timeRemaining;
    @Singular
    List<PropertyValue> propertyValues;

    /**
     * 定义当前模块的业务组件。
     */
    @Value
    @Builder
    public static class PropertyValue {
        BacnetPropertyIdentifier propertyIdentifier;
        Integer arrayIndex;
        Object value;
        String valueType;
        Map<String, Object> valueMetadata;
        Integer priority;
    }
}
