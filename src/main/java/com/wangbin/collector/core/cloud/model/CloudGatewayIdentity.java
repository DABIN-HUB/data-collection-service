package com.wangbin.collector.core.cloud.model;

/**
 * 采集服务在云平台上的网关身份。
 */
public record CloudGatewayIdentity(CloudDeviceIdentity identity, String secretRef) {

    /**
     * 执行当前业务逻辑。
     */
    public boolean valid() {
        return identity != null && identity.valid();
    }
}
