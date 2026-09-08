package com.wangbin.collector.api.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 实时表格紧凑增量响应。
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CompactRealtimeDeltaResponse {

    /**
     * 业务状态。
     */
    private String status;

    /**
     * 业务提示信息。
     */
    private String message;

    /**
     * 增量范围，取值为 all 或 device。
     */
    private String scope;

    /**
     * 单设备增量查询时的本地设备唯一标识。
     */
    private String deviceId;

    /**
     * 是否需要客户端重新请求完整紧凑快照。
     */
    private Boolean resetRequired;

    /**
     * 触发完整同步的原因。
     */
    private String resetReason;

    /**
     * 当前服务进程快照标识。
     */
    private String snapshotId;

    /**
     * 当前配置纪元。
     */
    private Long configEpoch;

    /**
     * 客户端请求携带的起始修订号。
     */
    private Long fromRevision;

    /**
     * 本轮增量查询上界修订号。
     */
    private Long revision;

    /**
     * 返回的变更行数。
     */
    private Integer changedCount;

    /**
     * 变更的实时表格点位行。
     */
    private List<CompactRealtimePointPayload> rows;

    /**
     * 响应生成时间戳，单位毫秒。
     */
    private Long timestamp;
}
