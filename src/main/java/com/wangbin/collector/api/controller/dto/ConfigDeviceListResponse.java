package com.wangbin.collector.api.controller.dto;

import com.wangbin.collector.common.domain.entity.DeviceInfo;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 配置中心设备列表响应数据。
 */
@Data
@Builder
public class ConfigDeviceListResponse {

    /**
     * 设备配置列表。
     */
    private List<DeviceInfo> devices;

    /**
     * 设备数量。
     */
    private Integer count;
}
