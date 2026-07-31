package com.wangbin.collector.core.collector.protocol.bacnet.domain;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.List;
import java.util.Map;

/**
 * 承载当前模块的数据传输内容。
 */
@Value
@Builder
public class BacnetReadPropertyMultipleResponse {

    @Singular
    List<ReadAccessResult> results;
    int invokeId;

    /**
     * 承载当前模块的数据传输内容。
     */
    @Value
    @Builder
    public static class ReadAccessResult {
        BacnetObjectType objectType;
        int objectInstance;
        @Singular
        List<PropertyValueResult> propertyResults;
    }

    /**
     * 承载当前模块的数据传输内容。
     */
    @Value
    @Builder
    public static class PropertyValueResult {
        BacnetPropertyIdentifier propertyIdentifier;
        Integer arrayIndex;
        Object value;
        String valueType;
        Map<String, Object> valueMetadata;
        boolean error;
        String errorMessage;
    }
}
