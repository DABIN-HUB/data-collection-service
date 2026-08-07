package com.wangbin.collector.core.connection.factory.provider;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.connection.adapter.ConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.TcpConnectionAdapter;
import com.wangbin.collector.core.connection.factory.ConnectionAdapterProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 通用 TCP 连接适配器 Provider。
 */
@Slf4j
@Component
public class GenericConnectionAdapterProvider implements ConnectionAdapterProvider {

    @Override
    public Set<String> supportedConnectionTypes() {
        return Set.of("TCP");
    }

    @Override
    public ConnectionAdapter<?> create(String connectionType, DeviceInfo deviceInfo, DeviceConnection connectionConfig) {
        try {
            return new TcpConnectionAdapter(deviceInfo, connectionConfig);
        } catch (Exception exception) {
            log.error("创建 TCP 连接失败: 设备={}", deviceInfo.getDeviceId(), exception);
            throw new CollectorException("创建 TCP 连接失败", deviceInfo.getDeviceId(), null);
        }
    }
}
