package com.wangbin.collector.core.collector.protocol.bacnet.domain;

import lombok.Value;

@Value
public class BacnetAddress {

    String rawAddress;
    String canonicalAddress;
    String objectType;
    int objectTypeId;
    int instanceNumber;
    String propertyIdentifier;
    int propertyIdentifierId;
    Integer arrayIndex;
    String driverDataType;
}
