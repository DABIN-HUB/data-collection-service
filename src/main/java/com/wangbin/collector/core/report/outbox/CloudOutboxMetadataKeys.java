package com.wangbin.collector.core.report.outbox;

/**
 * 发件箱与上报结果共享的元数据键。
 */
public final class CloudOutboxMetadataKeys {

    public static final String RAW_DEVICE_ID = "rawDeviceId";
    public static final String PRODUCT_KEY = "productKey";
    public static final String GATEWAY_DEVICE_ID = "gatewayDeviceId";
    public static final String SHADOW_VERSION = "shadowVersion";
    public static final String ACK_PENDING = "ackPending";
    public static final String ACK_TIMEOUT_MS = "ackTimeoutMs";
    public static final String ACK_COMMIT_ON = "ackCommitOn";

    private CloudOutboxMetadataKeys() {
    }
}
