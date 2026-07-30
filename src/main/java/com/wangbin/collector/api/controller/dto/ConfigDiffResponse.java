package com.wangbin.collector.api.controller.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.List;

/**
 * 配置差异响应
 */
@Data
@Builder
public class ConfigDiffResponse {

    private boolean deviceChanged;
    private boolean connectionChanged;
    private List<String> missingPointCodes = Collections.emptyList();
    private List<String> extraPointCodes = Collections.emptyList();
    private List<String> changedPointCodes = Collections.emptyList();
}
