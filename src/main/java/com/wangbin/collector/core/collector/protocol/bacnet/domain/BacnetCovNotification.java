package com.wangbin.collector.core.collector.protocol.bacnet.domain;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class BacnetCovNotification {

    int subscriberProcessIdentifier;
    int initiatingDeviceInstance;
    BacnetObjectType monitoredObjectType;
    int monitoredObjectInstance;
    Integer timeRemaining;
    @Singular
    List<PropertyValue> propertyValues;

    @Value
    @Builder
    public static class PropertyValue {
        BacnetPropertyIdentifier propertyIdentifier;
        Integer arrayIndex;
        Object value;
        String valueType;
        Integer priority;
    }
}
