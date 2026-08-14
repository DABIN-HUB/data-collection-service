package com.wangbin.collector.core.connection.factory.provider;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.connection.adapter.ConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.OmronFinsUdpConnectionAdapter;
import com.wangbin.collector.core.connection.factory.ConnectionAdapterProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * OMRON FINS 连接适配器 Provider。
 */
@Slf4j
@Component
public class OmronConnectionAdapterProvider implements ConnectionAdapterProvider {

    @Override
    public Set<String> supportedConnectionTypes() {
        return Set.of("OMRON_FINS");
    }

    @Override
    public ConnectionAdapter<?> create(String connectionType, DeviceInfo deviceInfo, DeviceConnection connectionConfig) {
        try {
            return new OmronFinsUdpConnectionAdapter(deviceInfo, connectionConfig);
        } catch (Exception exception) {
            log.error("创建 OMRON FINS 连接 失败:{}", deviceInfo.getDeviceId(), exception);
            throw new CollectorException("Create OMRON FINS connection failed", deviceInfo.getDeviceId(), null);
        }
    }
}
