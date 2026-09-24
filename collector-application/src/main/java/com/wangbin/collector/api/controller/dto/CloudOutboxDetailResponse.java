package com.wangbin.collector.api.controller.dto;

import com.wangbin.collector.core.report.outbox.CloudOutboxMessage;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class CloudOutboxDetailResponse {
    CloudOutboxItemResponse summary;
    long shadowVersion;
    long windowStart;
    long windowEnd;
    CloudOutboxMessage.ReportDataSnapshot reportData;
    java.util.List<CloudOutboxMessage.CloudOutboxCommit> commits;

    public static CloudOutboxDetailResponse from(CloudOutboxMessage message) {
        return builder().summary(CloudOutboxItemResponse.from(message)).shadowVersion(message.getShadowVersion())
                .windowStart(message.getWindowStart()).windowEnd(message.getWindowEnd())
                .reportData(message.getReportData()).commits(message.resolveCommits()).build();
    }
}
