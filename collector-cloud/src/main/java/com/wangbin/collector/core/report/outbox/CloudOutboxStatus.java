package com.wangbin.collector.core.report.outbox;

/**
 * 云端上报发件箱状态。
 */
public enum CloudOutboxStatus {

    /** 等待发送。 */
    PENDING,

    /** 已被分发并进入真实发布尝试窗口。 */
    PUBLISHING,

    /** 已发布，等待平台业务确认。 */
    WAITING_ACK,

    /** 上报配置暂不可用。 */
    WAITING_CONFIG,

    /** 超过重试上限，等待人工处理。 */
    ISOLATED
}
