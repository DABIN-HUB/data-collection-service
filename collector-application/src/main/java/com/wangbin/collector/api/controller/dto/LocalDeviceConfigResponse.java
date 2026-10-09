package com.wangbin.collector.api.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

/**
 * 本地临时设备配置响应数据。
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LocalDeviceConfigResponse {

    /**
     * 本地设备唯一标识。
     */
    private String deviceId;

    /**
     * 配置来源。
     */
    private String configSource;

    /**
     * 是否为本地临时配置。
     */
    private Boolean temporaryConfig;

    /**
     * 设备配置包。
     */
    private ConfigBundle bundle;

    /**
     * 保存后是否启动成功。
     */
    private Boolean started;

    /** 配置已经成功保存，与启动结果独立。 */
    private Boolean saved;

    /** 本次保存是否产生有效配置差异。 */
    private Boolean changed;

    /** 已提交的设备配置版本。 */
    private Long configVersion;

    /** 是否请求保存后启动。 */
    private Boolean startRequested;

    /** 启动结果：NOT_REQUESTED、ALREADY_RUNNING、ACCEPTED、RESTART_PENDING、STOP_SUPERSEDED 或 FAILED。 */
    private String startStatus;

    /** 启动失败说明，不影响 saved。 */
    private String startError;

    /** 启动后的真实运行快照，启动被接受不等于 ONLINE。 */
    private com.wangbin.collector.core.collector.runtime.DeviceRuntimeSnapshot runtime;

    /**
     * 点位数量。
     */
    private Integer pointCount;
}
