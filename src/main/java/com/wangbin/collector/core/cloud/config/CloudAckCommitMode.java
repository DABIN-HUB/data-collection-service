package com.wangbin.collector.core.cloud.config;

import java.util.Locale;

/**
 * 影子提交与云端 ACK 的关系。
 */
public enum CloudAckCommitMode {

    /**
     * MQTT 发布成功即提交，性能最高，默认用于高频属性链路。
     */
    PUBLISH_SUCCESS,

    /**
     * 预留模式：平台业务 ACK 成功后提交。
     */
    ACK_SUCCESS;

    public static CloudAckCommitMode from(String value) {
        if (value == null || value.isBlank()) {
            return PUBLISH_SUCCESS;
        }
        String normalized = value.trim()
                .replace('-', '_')
                .toUpperCase(Locale.ROOT);
        try {
            return CloudAckCommitMode.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            return PUBLISH_SUCCESS;
        }
    }
}
