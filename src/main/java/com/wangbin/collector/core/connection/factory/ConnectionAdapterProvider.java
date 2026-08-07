package com.wangbin.collector.core.connection.factory;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.connection.adapter.ConnectionAdapter;

import java.util.Set;

/**
 * 连接适配器提供者，按规范化后的 connectionType 创建对应连接适配器。
 */
public interface ConnectionAdapterProvider {

    /**
     * 返回当前 Provider 支持的规范 connectionType。
     *
     * @return 不可为空的 connectionType 集合
     */
    Set<String> supportedConnectionTypes();

    /**
     * 基于已经完成默认值处理和校验的配置创建连接适配器。
     *
     * @param connectionType 规范化后的 connectionType
     * @param deviceInfo 设备信息
     * @param connectionConfig 连接配置
     * @return 连接适配器
     */
    ConnectionAdapter<?> create(String connectionType, DeviceInfo deviceInfo, DeviceConnection connectionConfig);
}
