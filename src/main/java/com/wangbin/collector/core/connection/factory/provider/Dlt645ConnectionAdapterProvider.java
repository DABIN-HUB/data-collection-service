package com.wangbin.collector.core.connection.factory.provider;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.connection.adapter.ConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.Dlt645ConnectionAdapter;
import com.wangbin.collector.core.connection.factory.ConnectionAdapterProvider;
import com.wangbin.collector.core.connection.serial.SharedSerialChannelManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * DL/T 645 串口连接适配器 Provider。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Dlt645ConnectionAdapterProvider implements ConnectionAdapterProvider {

    private final SharedSerialChannelManager sharedSerialChannelManager;

    @Override
    public Set<String> supportedConnectionTypes() {
        return Set.of("DLT645_2007");
    }

    @Override
    public ConnectionAdapter<?> create(String connectionType, DeviceInfo deviceInfo, DeviceConnection connectionConfig) {
        try {
            return new Dlt645ConnectionAdapter(deviceInfo, connectionConfig, sharedSerialChannelManager);
        } catch (Exception exception) {
            log.error("创建 DL/T 645 连接失败: {}", deviceInfo.getDeviceId(), exception);
            throw new CollectorException("创建 DL/T 645 连接失败", deviceInfo.getDeviceId(), null);
        }
    }
}
