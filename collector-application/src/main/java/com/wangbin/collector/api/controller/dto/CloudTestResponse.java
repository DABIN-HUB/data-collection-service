package com.wangbin.collector.api.controller.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class CloudTestResponse {
    boolean enabled;
    boolean configured;
    Boolean connected;
    String message;
    long checkedAt;
}
