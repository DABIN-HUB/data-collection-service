package com.wangbin.collector.core.collector.protocol.bacnet.domain;

import lombok.Builder;
import lombok.Value;

/**
 * 承载当前模块的数据传输内容。
 */
@Value
@Builder
public class BacnetWritePropertyRequest {

    BacnetObjectType objectType;
    int objectInstance;
    BacnetPropertyIdentifier propertyIdentifier;
    Integer arrayIndex;
    Object value;
    String valueType;
    Integer priority;
    int invokeId;
    int remoteDeviceInstance;
}
