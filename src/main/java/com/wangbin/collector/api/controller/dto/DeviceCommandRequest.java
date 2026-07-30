package com.wangbin.collector.api.controller.dto;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 控制台手工设备命令请求。
 */
@Data
public class DeviceCommandRequest {
    @NotBlank(message = "命令不能为空")
    private String command;
    private Map<String, Object> params = new LinkedHashMap<>();
}
