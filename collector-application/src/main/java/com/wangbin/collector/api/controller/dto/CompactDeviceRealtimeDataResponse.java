package com.wangbin.collector.api.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 单设备实时表格紧凑快照响应。
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CompactDeviceRealtimeDataResponse {

    /**
     * 业务状态。
     */
    private String status;

    /**
     * 业务提示信息。
     */
    private String message;

    /**
     * 当前服务进程快照标识。
     */
    private String snapshotId;

    /**
     * 当前配置纪元。
     */
    private Long configEpoch;

    /**
     * 完整快照捕获时的全局修订号。
     */
    private Long revision;

    /**
     * 本地设备唯一标识。
     */
    private String deviceId;

    /**
     * 返回的点位行数。
     */
    private Integer dataCount;

    /**
     * 实时表格点位行。
     */
    private List<CompactRealtimePointPayload> rows;

    /** 设备健康及分层运行态；兼容原有 status。 */
    private String deviceHealth;
    private String transportState;
    private String protocolState;
    private String acquisitionState;
    private Long lastAttemptAt;
    private Long lastSuccessAt;
    private Long lastValueAt;

    /**
     * 响应生成时间戳，单位毫秒。
     */
    private Long timestamp;
}
