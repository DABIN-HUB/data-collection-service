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
    /** 编辑时的 JVM 配置版本；省略时保留旧调用方覆盖语义。 */
    private Long baseVersion;
    /** 本地 SQLite 编辑的持久修订基线。 */
    private Long baseRevision;

    public LocalDeviceConfigRequest(DeviceInfo device, DeviceConnection connection, List<DataPoint> points,
                                    boolean overwrite, boolean startAfterSave, Long baseVersion) {
        this(device, connection, points, overwrite, startAfterSave, baseVersion, null);
    }

    /** 兼容历史五参数构造方式。 */
    public LocalDeviceConfigRequest(DeviceInfo device, DeviceConnection connection, List<DataPoint> points,
                                    boolean overwrite, boolean startAfterSave) {
        this(device, connection, points, overwrite, startAfterSave, null, null);
    }
}
