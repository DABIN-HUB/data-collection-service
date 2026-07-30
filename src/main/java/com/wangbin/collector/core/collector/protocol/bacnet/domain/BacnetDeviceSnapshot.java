package com.wangbin.collector.core.collector.protocol.bacnet.domain;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder
public class BacnetDeviceSnapshot {

    int remoteDeviceInstance;
    Map<String, Object> deviceInfo;
    List<String> objectList;
    Map<String, Object> propertyCache;
    long snapshotAt;
}
