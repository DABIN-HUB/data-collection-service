package com.wangbin.collector.api.controller.dto;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** 单设备配置 Bundle 请求。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeviceConfigBundleRequest {
    private long baseVersion;
    @Valid
    @NotNull
    private DeviceInfo device;
    @Valid
    @NotNull
    private DeviceConnection connection;
    @Valid
    private List<DataPoint> points = new ArrayList<>();
}
