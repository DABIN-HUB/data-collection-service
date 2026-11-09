package com.wangbin.collector.core.config.persistence;

/** 设备列表和按 ID 定位所需的持久化基础信息；不包含连接或点位负载。 */
public record StoredDeviceSummary(String deviceId, String deviceJson, long revision) {
}
