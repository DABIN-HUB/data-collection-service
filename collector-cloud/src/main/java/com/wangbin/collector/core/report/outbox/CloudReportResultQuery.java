package com.wangbin.collector.core.report.outbox;

/**
 * 云上报终态历史的有界查询端口，云模块不依赖具体存储。
 */
@FunctionalInterface
public interface CloudReportResultQuery {

    /**
     * 查询终态历史；结果计数按稳定消息 ID 去重，只有真实业务 ACK 计入确认数。
     *
     * @param deviceId 本地设备 ID，为空表示所有设备
     * @param startTs 起始完成时间（毫秒，含）
     * @param endTs 截止完成时间（毫秒，含）
     * @param page 从一开始的页码
     * @param limit 每页上限
     * @return 历史页和完整性状态
     */
    CloudReportResultPage query(String deviceId, long startTs, long endTs, int page, int limit);
}
