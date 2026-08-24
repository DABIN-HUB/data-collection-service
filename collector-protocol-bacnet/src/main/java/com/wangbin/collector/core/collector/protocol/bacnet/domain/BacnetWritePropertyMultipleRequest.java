package com.wangbin.collector.core.collector.protocol.bacnet.domain;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.List;

/**
 * 承载当前模块的数据传输内容。
 */
@Value
@Builder
public class BacnetWritePropertyMultipleRequest {

    @Singular
    List<WriteAccessSpec> writeAccessSpecifications;
    int invokeId;
    int remoteDeviceInstance;

    /**
     * 定义当前模块的业务组件。
     */
    @Value
    @Builder
    public static class WriteAccessSpec {
        BacnetObjectType objectType;
        int objectInstance;
        @Singular
        List<PropertyValueSpec> propertyValues;
    }

    /**
     * 定义当前模块的业务组件。
     */
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
