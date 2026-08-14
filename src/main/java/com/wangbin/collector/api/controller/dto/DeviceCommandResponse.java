package com.wangbin.collector.api.controller.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 协议命令执行响应。
 */
@Data
@Builder
public class DeviceCommandResponse {

    /**
     * 本地设备唯一标识。
     */
    private String deviceId;

    /**
     * 命令名称。
     */
    private String command;

    /**
     * 命令参数，字段由协议命令定义。
     */
    private Map<String, Object> params;

    /**
     * 命令执行结果，结构由协议能力决定。
     */
    private Object result;
}
