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
    /** JVM 运行版本，保留旧客户端兼容语义。 */
    private long baseVersion;
    /** 本地 SQLite 编辑时取得的持久修订；远端配置不使用。 */
    private Long baseRevision;
    @Valid
    @NotNull
    private DeviceInfo device;
    @Valid
    @NotNull
    private DeviceConnection connection;
    @Valid
    private List<DataPoint> points = new ArrayList<>();

    public DeviceConfigBundleRequest(long baseVersion, DeviceInfo device, DeviceConnection connection,
                                     List<DataPoint> points) {
        this(baseVersion, null, device, connection, points);
    }
}
