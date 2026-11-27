package com.wangbin.collector.api.application;

import com.wangbin.collector.core.report.outbox.CloudReportResultPage;
import com.wangbin.collector.core.report.outbox.CloudReportResultQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 云上报结果历史查询入口，不把 Outbox 待办消息当作已完成历史。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CloudReportResultApplicationService {

    private static final long DEFAULT_WINDOW_MILLIS = 24L * 60 * 60 * 1000;
    private static final long MAX_WINDOW_MILLIS = 31L * 24 * 60 * 60 * 1000;
    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_PAGE = 10000;

    private final ObjectProvider<CloudReportResultQuery> queryProvider;

    /**
     * 解析默认时间窗口和分页边界，结果存储不可用时保持计数未知。
     */
    public CloudReportResultPage query(String deviceId, Long startTs, Long endTs, Integer page, Integer limit) {
        long now = System.currentTimeMillis();
        long end = endTs == null ? now : endTs;
        long start = startTs == null ? Math.max(1L, end - DEFAULT_WINDOW_MILLIS) : startTs;
        int pageNumber = page == null ? 1 : page;
        int pageSize = limit == null ? MAX_PAGE_SIZE : limit;
        if (start <= 0 || end < start || end - start > MAX_WINDOW_MILLIS || end > now + 60_000L
                || pageNumber <= 0 || pageNumber > MAX_PAGE || pageSize <= 0 || pageSize > MAX_PAGE_SIZE
                || (long) pageNumber * pageSize > Integer.MAX_VALUE
                || (deviceId != null && deviceId.length() > 128)) {
            throw new IllegalArgumentException("云上报结果查询的设备、时间范围或分页参数无效");
        }
        CloudReportResultQuery query = queryProvider.getIfAvailable();
        if (query == null) {
            return CloudReportResultPage.unavailable();
        }
        try {
            return query.query(deviceId == null || deviceId.isBlank() ? null : deviceId.trim(),
                    start, end, pageNumber, pageSize);
        } catch (RuntimeException exception) {
            log.warn("云上报结果历史查询失败，返回记录不完整状态", exception);
            return CloudReportResultPage.unavailable();
        }
    }
}
