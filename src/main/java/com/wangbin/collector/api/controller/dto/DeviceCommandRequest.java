package com.wangbin.collector.api.controller.dto;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manual device command request from the admin console.
 */
@Data
public class DeviceCommandRequest {
    private String command;
    private Map<String, Object> params = new LinkedHashMap<>();
}
