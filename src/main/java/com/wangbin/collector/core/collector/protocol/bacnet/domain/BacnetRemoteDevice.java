package com.wangbin.collector.core.collector.protocol.bacnet.domain;

import lombok.Builder;
import lombok.Value;

import java.net.InetSocketAddress;

@Value
@Builder
public class BacnetRemoteDevice {

    int deviceInstance;
    InetSocketAddress socketAddress;
    Integer maxApduLengthAccepted;
    Integer vendorId;
    String segmentationSupported;
    boolean discoveredByWhoIs;
}
