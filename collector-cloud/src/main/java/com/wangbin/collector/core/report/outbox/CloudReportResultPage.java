package com.wangbin.collector.core.report.outbox;

import java.util.List;

/**
 * 云上报终态历史查询页；计数缺失时必须以 null 表示未知。
 *
 * @param items 当前页记录
 * @param total 时间范围内已落库的去重消息数，存储或补写状态不可确认时为 null
 * @param ackCount 范围内已落库且完成业务 ACK 提交的去重消息数，存储或补写状态不可确认时为 null
 * @param complete 是否能够确认记录历史完整；即使当前计数可用，也不得推断过往双存储故障没有漏记
 * @param pendingCount 等待补写的结果数；Redis 不可查询时为 null
 * @param missingCount 已知无法补写的结果数；Redis 不可查询时为 null
 */
public record CloudReportResultPage(
        List<CloudReportResult> items,
        Long total,
        Long ackCount,
        boolean complete,
        Long pendingCount,
        Long missingCount) {

    public CloudReportResultPage {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /**
     * 存储未启用或无法查询时的失败关闭响应。
     */
    public static CloudReportResultPage unavailable() {
        return new CloudReportResultPage(List.of(), null, null, false, null, null);
    }
}
