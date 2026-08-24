package com.wangbin.collector.common.domain.cloud;

import java.util.Objects;

/**
 * 云平台设备身份，严格对应 productKey + deviceName。
 */
public record CloudDeviceIdentity(String productKey, String deviceName) {

    public CloudDeviceIdentity {
        productKey = normalize(productKey);
        deviceName = normalize(deviceName);
    }

    /**
     * 云端身份必须同时具备产品标识和设备名称。
     */
    public boolean valid() {
        return productKey != null && !productKey.isBlank()
                && deviceName != null && !deviceName.isBlank();
    }

    /**
     * 用于云端主题、拓扑和缓存索引的稳定组合键。
     */
    public String key() {
        return productKey + "/" + deviceName;
    }

    /**
     * 创建并规范化云端设备身份。
     */
    public static CloudDeviceIdentity of(String productKey, String deviceName) {
        return new CloudDeviceIdentity(productKey, deviceName);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CloudDeviceIdentity that)) {
            return false;
        }
        return Objects.equals(productKey, that.productKey)
                && Objects.equals(deviceName, that.deviceName);
    }
}
