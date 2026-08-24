package com.wangbin.collector.common.domain.cloud;

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
     * 返回当前设备绑定的云端身份。
     */
    public CloudDeviceIdentity identity() {
        return CloudDeviceIdentity.of(productKey, deviceName);
    }

    /**
     * 启用云端上报时必须具备完整身份和设备类型。
     */
    public boolean valid() {
        return enabled
                && StringUtils.hasText(productKey)
                && StringUtils.hasText(deviceName)
                && deviceType != null;
    }

    /**
     * 是否按直连设备或网关设备语义上报。
     */
    public boolean gatewayDevice() {
        return deviceType == CloudDeviceType.GATEWAY || deviceType == CloudDeviceType.DIRECT;
    }

    /**
     * 是否按子设备语义参与上报。
     */
    public boolean subDevice() {
        return deviceType == CloudDeviceType.SUB_DEVICE || deviceType == CloudDeviceType.LOGICAL_SUB_DEVICE;
    }
}
