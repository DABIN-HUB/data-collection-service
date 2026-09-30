package com.wangbin.collector.api.controller.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class CloudFlushResponse {
    boolean accepted;
    long pendingBefore;
    long isolated;
    long triggeredAt;
}
