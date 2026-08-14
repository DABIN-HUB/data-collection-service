package com.wangbin.collector.core.collector.protocol.bacnet.domain;

import lombok.Builder;
import lombok.Value;

/**
 * 承载当前模块的数据传输内容。
 */
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
