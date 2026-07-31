package com.wangbin.collector.core.collector.protocol.bacnet.domain;

import lombok.Value;

/**
 * 定义当前模块的业务组件。
 */
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
