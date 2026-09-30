package com.wangbin.collector.api.controller.dto;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** 单设备配置 Bundle 响应，包含当前 JVM 配置版本。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceConfigBundleResponse {
    private String deviceId;
    private long configVersion;
    private DeviceInfo device;
    private DeviceConnection connection;
    @Builder.Default
    private List<DataPoint> points = new ArrayList<>();
    private String configSource;
    private boolean temporaryConfig;
}
