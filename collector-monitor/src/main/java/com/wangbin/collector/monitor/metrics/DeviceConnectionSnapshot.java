package com.wangbin.collector.monitor.metrics;

import com.wangbin.collector.common.domain.enums.ConnectionStatus;
import com.wangbin.collector.core.collector.runtime.DeviceRuntimeSnapshot;
import lombok.Builder;
import lombok.Data;

/**
 * 单个设备连接状态快照。
 */
@Data
@Builder
public class DeviceConnectionSnapshot {

    private final String deviceId;
    private final ConnectionStatus status;
    private final boolean connected;

    @Builder.Default
    private final boolean expectedOnly = false;

    private final long lastActivityTime;
    private final long idleTime;
    private final long bytesSent;
    private final long bytesReceived;
    private final long errors;
    private final double successRate;
    private final long connectionDuration;
    /** 与设备列表、实时数据共用采集事实，不以连接计数代替健康。 */
    private final DeviceRuntimeSnapshot runtime;
}
