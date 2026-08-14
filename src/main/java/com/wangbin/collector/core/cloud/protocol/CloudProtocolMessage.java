package com.wangbin.collector.core.cloud.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.wangbin.collector.common.domain.cloud.CloudDeviceIdentity;

/**
 * 云平台协议解码后的统一消息信封。
 */
public record CloudProtocolMessage(
        String id,
        String version,
        String method,
        CloudDeviceIdentity identity,
        JsonNode payload,
        JsonNode params) {
}
