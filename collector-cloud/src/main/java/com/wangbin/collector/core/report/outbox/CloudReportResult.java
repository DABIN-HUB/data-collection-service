package com.wangbin.collector.core.report.outbox;

import java.util.List;

/**
 * 云上报终态记录的非敏感元数据。
 *
 * @param messageId 稳定业务消息 ID
 * @param localDeviceIds 消息关联的本地设备 ID
 * @param cloudDeviceId 云端设备标识
 * @param gatewayDeviceId 网关设备标识
 * @param status 终态
 * @param publishedAt 实际发布成功时间
 * @param ackAt 有效业务 ACK 到达时间
 * @param createdAt 原始消息创建时间，用于稳定的时序主键
 * @param completedAt 终态提交时间
 * @param attempts 调度尝试次数
 * @param errorMessage 最终错误信息
 */
public record CloudReportResult(
        String messageId,
        List<String> localDeviceIds,
        String cloudDeviceId,
        String gatewayDeviceId,
        CloudReportResultStatus status,
        long publishedAt,
        long ackAt,
        long createdAt,
        long completedAt,
        int attempts,
        String errorMessage) {

    /**
     * 创建不可变的设备 ID 快照，避免上报状态变化后影响历史记录。
     */
    public CloudReportResult {
        localDeviceIds = localDeviceIds == null ? List.of() : List.copyOf(localDeviceIds);
    }
}
