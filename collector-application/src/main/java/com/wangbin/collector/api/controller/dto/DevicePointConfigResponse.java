package com.wangbin.collector.api.controller.dto;

import com.wangbin.collector.common.domain.entity.DataPoint;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 设备点位配置响应数据。
 */
@Data
@Builder
public class DevicePointConfigResponse {

    /**
     * 本地设备唯一标识。
     */
    private String deviceId;

    /**
     * 点位数量。
     */
    private Integer count;

    /**
     * 点位配置列表。
     */
    private List<DataPoint> points;
}
