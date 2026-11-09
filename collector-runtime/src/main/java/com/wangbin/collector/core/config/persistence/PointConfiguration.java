package com.wangbin.collector.core.config.persistence;

/** pointId 是全局稳定身份；status 是可空配置值，不是实时采集状态。 */
public record PointConfiguration(String deviceId, String pointId, int sortOrder, Integer status, String configJson) {
    public PointConfiguration {
        ConfigurationModelChecks.identity(deviceId);
        ConfigurationModelChecks.identity(pointId);
        ConfigurationModelChecks.json(configJson);
        if (sortOrder < 0) throw new IllegalArgumentException("点位排序不能为负数");
    }
    @Override public String toString() { return "PointConfiguration[已隐藏配置]"; }
}
