package com.wangbin.collector.api.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 单设备配置提交结果。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceConfigCommitResponse {
    private String deviceId;
    private long previousVersion;
    private long configVersion;
    /** 本次提交后的 SQLite 设备持久修订。 */
    private long persistedRevision;
    private int pointCount;
}
