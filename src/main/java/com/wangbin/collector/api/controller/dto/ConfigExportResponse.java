package com.wangbin.collector.api.controller.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.List;

/**
 * 导出响应
 */
@Data
@Builder
public class ConfigExportResponse {

    @Builder.Default
    private List<ConfigBundle> bundles = Collections.emptyList();
}
