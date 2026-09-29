package com.wangbin.collector.api.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 设备实时数据查询响应。
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeviceRealtimeDataResponse {

    /**
     * 业务状态。
     */
    private String status;

    /**
     * 业务提示信息。
     */
    private String message;

    /**
     * 本地设备唯一标识。
     */
    private String deviceId;

    /**
     * 返回的点位数量。
     */
    private Integer dataCount;

    /**
     * 点位实时数据，键为稳定 pointId。
     */
    private Map<String, PointRealtimePayload> data;

    /** 当前设备健康状态；与现有业务 status 字段并存。 */
    private String deviceHealth;

    /** 传输、协议会话和采集状态，不以连接成功推断点位有效。 */
    private String transportState;
    private String protocolState;
    private String acquisitionState;
    private Long lastAttemptAt;
    /** 最近一次成功进入实时缓存的有效采集时间。 */
    private Long lastSuccessAt;
    private Long lastValueAt;

    /**
     * 响应生成时间戳，单位毫秒。
     */
    private Long timestamp;
}
