package com.wangbin.collector.core.connection.factory.provider;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.connection.adapter.ConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.MitsubishiMcConnectionAdapter;
import com.wangbin.collector.core.connection.factory.ConnectionAdapterProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Mitsubishi MC 连接适配器 Provider。
 */
@Slf4j
@Component
public class MitsubishiConnectionAdapterProvider implements ConnectionAdapterProvider {

    @Override
    public Set<String> supportedConnectionTypes() {
        return Set.of("MITSUBISHI_MC");
    }

    @Override
    public ConnectionAdapter<?> create(String connectionType, DeviceInfo deviceInfo, DeviceConnection connectionConfig) {
        try {
            return new MitsubishiMcConnectionAdapter(deviceInfo, connectionConfig);
        } catch (Exception exception) {
            log.error("创建 Mitsubishi MC 连接 失败:{}", deviceInfo.getDeviceId(), exception);
            throw new CollectorException("Create Mitsubishi MC connection failed", deviceInfo.getDeviceId(), null);
        }
    }
}
