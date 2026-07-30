package com.wangbin.collector.core.collector.protocol.bacnet.domain;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class BacnetReadPropertyMultipleRequest {

    @Singular
    List<ReadAccessSpec> accessSpecifications;
    int invokeId;
    int remoteDeviceInstance;

    @Value
    @Builder
    public static class ReadAccessSpec {
        BacnetObjectType objectType;
        int objectInstance;
        @Singular
        List<PropertyReferenceSpec> propertyReferences;
    }

    @Value
    @Builder
    public static class PropertyReferenceSpec {
        BacnetPropertyIdentifier propertyIdentifier;
        Integer arrayIndex;
    }
}
