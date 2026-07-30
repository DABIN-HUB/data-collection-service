package com.wangbin.collector.core.collector.protocol.bacnet.domain;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class BacnetReadPropertyRequest {

    BacnetObjectType objectType;
    int objectInstance;
    BacnetPropertyIdentifier propertyIdentifier;
    Integer arrayIndex;
    int invokeId;
    int remoteDeviceInstance;
}
