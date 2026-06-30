package com.wangbin.collector.core.collector.protocol.bacnet.domain;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class BacnetReadPropertyMultipleResponse {

    @Singular
    List<ReadAccessResult> results;
    int invokeId;

    @Value
    @Builder
    public static class ReadAccessResult {
        BacnetObjectType objectType;
        int objectInstance;
        @Singular
        List<PropertyValueResult> propertyResults;
    }

    @Value
    @Builder
    public static class PropertyValueResult {
        BacnetPropertyIdentifier propertyIdentifier;
        Integer arrayIndex;
        Object value;
        String valueType;
        boolean error;
        String errorMessage;
    }
}
