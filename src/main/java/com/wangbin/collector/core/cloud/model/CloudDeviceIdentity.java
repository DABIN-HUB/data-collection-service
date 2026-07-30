package com.wangbin.collector.core.cloud.model;

import java.util.Objects;

/**
 * 云平台设备身份，严格对应 productKey + deviceName。
 */
public record CloudDeviceIdentity(String productKey, String deviceName) {

    public CloudDeviceIdentity {
        productKey = normalize(productKey);
        deviceName = normalize(deviceName);
    }

    public boolean valid() {
        return productKey != null && !productKey.isBlank()
                && deviceName != null && !deviceName.isBlank();
    }

    public String key() {
        return productKey + "/" + deviceName;
    }

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
