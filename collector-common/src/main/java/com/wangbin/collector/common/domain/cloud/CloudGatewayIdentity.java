package com.wangbin.collector.common.domain.cloud;

/**
 * 采集服务在云平台上的网关身份。
 */
public record CloudGatewayIdentity(CloudDeviceIdentity identity, String secretRef) {

    /**
     * 网关身份有效时才能发布拓扑和子设备生命周期消息。
     */
    public boolean valid() {
        return identity != null && identity.valid();
    }
}
