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

/**
 * Request payload for local temporary device configuration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LocalDeviceConfigRequest {

    private DeviceInfo device;
    private DeviceConnection connection;
    @Builder.Default
    private List<DataPoint> points = new ArrayList<>();
    private boolean overwrite;
    private boolean startAfterSave;
}
