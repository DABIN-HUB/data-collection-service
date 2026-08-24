package com.wangbin.collector.api.controller.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.List;

/**
 * 导入执行结果
 */
@Data
@Builder
public class ConfigImportResult {

    private int total;
    private int success;
    @Builder.Default
    private List<String> failedDevices = Collections.emptyList();
}
