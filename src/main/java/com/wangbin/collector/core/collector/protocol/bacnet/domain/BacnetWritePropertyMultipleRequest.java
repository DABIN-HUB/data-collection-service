package com.wangbin.collector.core.collector.protocol.bacnet.domain;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class BacnetWritePropertyMultipleRequest {

    @Singular
    List<WriteAccessSpec> writeAccessSpecifications;
    int invokeId;
    int remoteDeviceInstance;

    @Value
    @Builder
    public static class WriteAccessSpec {
        BacnetObjectType objectType;
        int objectInstance;
        @Singular
        List<PropertyValueSpec> propertyValues;
    }

    @Value
    @Builder
    public static class PropertyValueSpec {
        BacnetPropertyIdentifier propertyIdentifier;
        Integer arrayIndex;
        Object value;
        String valueType;
        Integer priority;
    }
}
