package com.wangbin.collector.core.report.outbox;

/**
 * 云上报终态记录状态。
 */
public enum CloudReportResultStatus {

    /** 上报处理器确认发送完成，但未以业务 ACK 作为提交依据。 */
    PUBLISHED,

    /** 已收到有效业务 ACK 并完成提交。 */
    ACK_CONFIRMED,

    /** 达到重试上限后仍未完成。 */
    FAILED
}
