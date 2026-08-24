package com.wangbin.collector.api.controller.dto;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地临时设备配置请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LocalDeviceConfigRequest {

    @Valid
    @NotNull(message = "设备基础信息不能为空")
    private DeviceInfo device;
    @Valid
    @NotNull(message = "设备连接参数不能为空")
    private DeviceConnection connection;
    @Valid
    @Builder.Default
    private List<DataPoint> points = new ArrayList<>();
    private boolean overwrite;
    private boolean startAfterSave;
}
