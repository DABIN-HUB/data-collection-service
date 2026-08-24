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
public class BacnetReadPropertyMultipleRequest {

    @Singular
    List<ReadAccessSpec> accessSpecifications;
    int invokeId;
    int remoteDeviceInstance;

    /**
     * 定义当前模块的业务组件。
     */
    @Value
    @Builder
    public static class ReadAccessSpec {
        BacnetObjectType objectType;
        int objectInstance;
        @Singular
        List<PropertyReferenceSpec> propertyReferences;
    }

    /**
     * 定义当前模块的业务组件。
     */
    @Value
    @Builder
    public static class PropertyReferenceSpec {
        BacnetPropertyIdentifier propertyIdentifier;
        Integer arrayIndex;
    }
}
