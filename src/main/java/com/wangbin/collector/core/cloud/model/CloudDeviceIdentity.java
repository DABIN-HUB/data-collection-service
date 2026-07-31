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

    /**
     * 执行当前业务逻辑。
     */
    public boolean valid() {
        return productKey != null && !productKey.isBlank()
                && deviceName != null && !deviceName.isBlank();
    }

    /**
     * 执行当前业务逻辑。
     */
    public String key() {
        return productKey + "/" + deviceName;
    }

    /**
     * 创建并返回业务对象。
     */
    public static CloudDeviceIdentity of(String productKey, String deviceName) {
        return new CloudDeviceIdentity(productKey, deviceName);
    }

    /**
     * 解析或转换业务数据。
     */
    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 执行当前业务逻辑。
     */
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
