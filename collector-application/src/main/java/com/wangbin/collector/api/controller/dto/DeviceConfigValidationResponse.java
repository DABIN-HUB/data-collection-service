package com.wangbin.collector.api.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** 单设备配置校验结果。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceConfigValidationResponse {
    private boolean valid;
    private long currentVersion;
    @Builder.Default
    private List<String> errors = new ArrayList<>();
    @Builder.Default
    private List<String> warnings = new ArrayList<>();
}
