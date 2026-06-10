package com.wangbin.collector.api.controller.dto;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manual point write request from the admin console.
 */
@Data
public class PointWriteRequest {
    private Object value;
    private Map<String, Object> values = new LinkedHashMap<>();
}
