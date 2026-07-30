package com.wangbin.collector.core.config.model;

/**
 * 配置加载结果
 *
 * @param status 配置加载状态
 * @param snapshot 配置快照
 * @param sourceVersion 配置来源版本
 * @param loadedAt 加载完成时间
 * @param errorMessage 错误信息
 */
public record ConfigLoadResult(
        ConfigLoadStatus status,
        ConfigSnapshot snapshot,
        String sourceVersion,
        long loadedAt,
        String errorMessage) {

    public static ConfigLoadResult success(ConfigSnapshot snapshot) {
        String version = snapshot == null ? null
                : Integer.toUnsignedString(snapshot.hashCode(), 16);
        return new ConfigLoadResult(ConfigLoadStatus.SUCCESS, snapshot, version,
                System.currentTimeMillis(), null);
    }

    public static ConfigLoadResult notModified(String sourceVersion) {
        return new ConfigLoadResult(ConfigLoadStatus.NOT_MODIFIED, null, sourceVersion,
                System.currentTimeMillis(), null);
    }

    public static ConfigLoadResult failed(String errorMessage) {
        return new ConfigLoadResult(ConfigLoadStatus.FAILED, null, null,
                System.currentTimeMillis(), errorMessage);
    }
}
