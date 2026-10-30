package com.wangbin.collector.config.sqlite.mapper;

/** MyBatis 设备根记录投影；连接和点位由同一事务中的专用查询补齐。 */
public record DeviceRow(String deviceId, String deviceJson, long revision) {
}
