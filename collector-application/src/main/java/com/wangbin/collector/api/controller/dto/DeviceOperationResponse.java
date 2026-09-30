package com.wangbin.collector.api.controller.dto;

import com.wangbin.collector.core.collector.runtime.DeviceRuntimeSnapshot;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 设备启停操作结果。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceOperationResponse {
    private String operationId;
    private String deviceId;
    private String action;
    private boolean accepted;
    private long acceptedAt;
    private long completedAt;
    private DeviceRuntimeSnapshot runtime;
}
