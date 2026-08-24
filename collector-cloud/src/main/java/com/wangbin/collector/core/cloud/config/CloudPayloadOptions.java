package com.wangbin.collector.core.cloud.config;

import com.wangbin.collector.common.enums.QualityEnum;
import com.wangbin.collector.core.report.config.ReportProperties;

import java.util.Map;

/**
 * 云平台上报 payload 精简策略。
 */
public record CloudPayloadOptions(
        CloudPayloadProfile profile,
        CloudPayloadFieldMode includeQuality,
        boolean includePropertyTs,
        boolean includeMetadata,
        boolean includeMessageId) {

    /**
     * 执行当前业务逻辑。
     */
    public static CloudPayloadOptions defaults() {
        return new CloudPayloadOptions(
                CloudPayloadProfile.COMPACT,
                CloudPayloadFieldMode.ON_ERROR,
                false,
                false,
                true);
    }

    /**
     * 创建并返回业务对象。
     */
    public static CloudPayloadOptions from(ReportProperties.Cloud.Payload payload) {
        if (payload == null) {
            return defaults();
        }
        return new CloudPayloadOptions(
                CloudPayloadProfile.from(payload.getProfile()),
                CloudPayloadFieldMode.from(payload.getIncludeQuality()),
                payload.isIncludePropertyTs(),
                payload.isIncludeMetadata(),
                payload.isIncludeMessageId());
    }

    /**
     * 执行当前业务逻辑。
     */
    public boolean includeQuality(Map<String, String> qualityMap) {
        if (includeQuality == CloudPayloadFieldMode.ALWAYS) {
            return qualityMap != null && !qualityMap.isEmpty();
        }
        if (includeQuality == CloudPayloadFieldMode.NEVER || qualityMap == null || qualityMap.isEmpty()) {
            return false;
        }
        return qualityMap.values().stream().anyMatch(this::isAbnormalQuality);
    }

    /**
     * 执行当前业务逻辑。
     */
    public boolean includeTimestamp() {
        return profile != CloudPayloadProfile.COMPACT;
    }

    private boolean isAbnormalQuality(String quality) {
        if (quality == null || quality.isBlank()) {
            return false;
        }
        String text = quality.trim();
        return !QualityEnum.GOOD.getText().equalsIgnoreCase(text)
                && !QualityEnum.GOOD.name().equalsIgnoreCase(text)
                && !"0".equals(text);
    }
}
