package com.wangbin.collector.core.cloud.model;

import lombok.Data;
import org.springframework.util.StringUtils;

/**
 * 设备级云平台上报目标配置。
 */
@Data
public class CloudTargetConfig {

    /**
     * 是否启用该采集设备的云端上报。
     */
    private boolean enabled;

    /**
     * 云平台设备类型，决定该设备按网关还是子设备语义上报。
     */
    private CloudDeviceType deviceType = CloudDeviceType.SUB_DEVICE;

    /**
     * 云平台产品标识。
     */
    private String productKey;

    /**
     * 云平台设备名称。
     */
    private String deviceName;

    /**
     * 子设备是否参与网关拓扑注册。
     */
    private boolean topologyEnabled = true;

    /**
     * 执行当前业务逻辑。
     */
    public CloudDeviceIdentity identity() {
        return CloudDeviceIdentity.of(productKey, deviceName);
    }

    /**
     * 执行当前业务逻辑。
     */
    public boolean valid() {
        return enabled
                && StringUtils.hasText(productKey)
                && StringUtils.hasText(deviceName)
                && deviceType != null;
    }

    /**
     * 执行当前业务逻辑。
     */
    public boolean gatewayDevice() {
        return deviceType == CloudDeviceType.GATEWAY || deviceType == CloudDeviceType.DIRECT;
    }

    /**
     * 执行当前业务逻辑。
     */
    public boolean subDevice() {
        return deviceType == CloudDeviceType.SUB_DEVICE || deviceType == CloudDeviceType.LOGICAL_SUB_DEVICE;
    }
}
