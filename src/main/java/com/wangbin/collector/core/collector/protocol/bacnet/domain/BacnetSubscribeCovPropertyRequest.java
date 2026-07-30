package com.wangbin.collector.core.collector.protocol.bacnet.domain;

import lombok.Builder;
import lombok.Value;

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
