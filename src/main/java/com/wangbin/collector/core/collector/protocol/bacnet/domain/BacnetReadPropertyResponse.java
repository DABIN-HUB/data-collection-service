package com.wangbin.collector.core.collector.protocol.bacnet.domain;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

@Value
@Builder
public class BacnetReadPropertyResponse {

    BacnetObjectType objectType;
    int objectInstance;
    BacnetPropertyIdentifier propertyIdentifier;
    Integer arrayIndex;
    Object value;
    String valueType;
    Map<String, Object> valueMetadata;
    int invokeId;
}
