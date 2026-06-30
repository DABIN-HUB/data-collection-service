package com.wangbin.collector.core.collector.protocol.bacnet.domain;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class BacnetReadPropertyResponse {

    BacnetObjectType objectType;
    int objectInstance;
    BacnetPropertyIdentifier propertyIdentifier;
    Integer arrayIndex;
    Object value;
    String valueType;
    int invokeId;
}
