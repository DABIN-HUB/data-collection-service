package com.wangbin.collector.core.cloud.protocol.alink.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.wangbin.collector.core.cloud.model.CloudDeviceIdentity;
import com.wangbin.collector.core.cloud.protocol.alink.AlinkMethod;

/**
 * 解码后的 Alink 消息信封。
 */
public record AlinkMessageEnvelope(String id,
                                   String version,
                                   AlinkMethod method,
                                   CloudDeviceIdentity identity,
                                   JsonNode payload,
                                   JsonNode params) {
}
