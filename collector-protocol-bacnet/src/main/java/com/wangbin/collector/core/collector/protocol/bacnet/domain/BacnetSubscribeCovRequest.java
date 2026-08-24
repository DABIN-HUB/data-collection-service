package com.wangbin.collector.core.collector.protocol.bacnet.domain;

import lombok.Builder;
import lombok.Value;

/**
 * 承载当前模块的数据传输内容。
 */
@Value
@Builder
public class BacnetSubscribeCovRequest {

    int subscriberProcessIdentifier;
    BacnetObjectType objectType;
    int objectInstance;
    boolean issueConfirmedNotifications;
    Integer lifetimeSeconds;
    int invokeId;
    int remoteDeviceInstance;
}
