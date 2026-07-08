package com.wangbin.collector.core.cloud.protocol.alink.topic;

import com.wangbin.collector.core.cloud.model.CloudDeviceIdentity;
import com.wangbin.collector.core.cloud.protocol.alink.AlinkMethod;

/**
 * 解析后的 Alink topic。
 */
public record AlinkTopic(String rawTopic,
                         String prefix,
                         CloudDeviceIdentity identity,
                         AlinkMethod method,
                         boolean reply) {
}
