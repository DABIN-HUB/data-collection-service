package com.wangbin.collector.core.config.model;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 可恢复的本地设备配置快照。
 *
 * <p>本地设备不受远端配置同步管理，但不能因为采集服务重启而丢失。
 * 该快照只保存设备、连接和点位配置，不保存运行态或实时数据。</p>
 */
public record LocalDeviceConfigSnapshot(DeviceInfo device,
                                        DeviceConnection connection,
                                        List<DataPoint> points) {

    /**
     * 从设备上下文创建可持久化快照。
     */
    public static LocalDeviceConfigSnapshot from(DeviceContext context) {
        return new LocalDeviceConfigSnapshot(
                context.getDeviceInfo(),
                context.copyConnectionConfig(),
                context.copyDataPoints());
    }

    /**
     * 转换为运行时配置上下文。
     */
    public DeviceContext toContext() {
        return DeviceContext.of(device, connection,
                points == null ? Collections.emptyList() : new ArrayList<>(points));
    }
}
