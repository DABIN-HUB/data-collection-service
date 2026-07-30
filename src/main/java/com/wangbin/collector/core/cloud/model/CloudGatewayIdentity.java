package com.wangbin.collector.core.cloud.model;

/**
 * 采集服务在云平台上的网关身份。
 */
public record CloudGatewayIdentity(CloudDeviceIdentity identity, String secretRef) {

    public boolean valid() {
        return identity != null && identity.valid();
    }
}
