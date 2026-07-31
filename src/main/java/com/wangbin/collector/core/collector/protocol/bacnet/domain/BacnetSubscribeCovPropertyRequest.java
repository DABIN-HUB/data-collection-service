package com.wangbin.collector.core.collector.protocol.bacnet.domain;

import lombok.Builder;
import lombok.Value;

/**
 * 承载当前模块的数据传输内容。
 */
@Value
@Builder
public class BacnetSubscribeCovPropertyRequest {

    int subscriberProcessIdentifier;
    BacnetObjectType objectType;
    int objectInstance;
    BacnetPropertyIdentifier propertyIdentifier;
    Integer arrayIndex;
    boolean issueConfirmedNotifications;
    Integer lifetimeSeconds;
    Double covIncrement;
    int invokeId;
    int remoteDeviceInstance;
}
