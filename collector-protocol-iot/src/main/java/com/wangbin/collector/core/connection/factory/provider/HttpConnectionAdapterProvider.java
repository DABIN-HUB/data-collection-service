package com.wangbin.collector.core.connection.factory.provider;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.connection.adapter.ConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.HttpConnectionAdapter;
import com.wangbin.collector.core.connection.factory.ConnectionAdapterProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.Executor;

/**
 * HTTP 连接适配器 Provider。
 */
@Slf4j
@Component
public class HttpConnectionAdapterProvider implements ConnectionAdapterProvider {

    @Nullable
    private final Executor ioExecutor;

    public HttpConnectionAdapterProvider(@Qualifier("ioIntensiveExecutor") @Nullable Executor ioExecutor) {
        this.ioExecutor = ioExecutor;
    }

    @Override
    public Set<String> supportedConnectionTypes() {
        return Set.of("HTTP");
    }

    @Override
    public ConnectionAdapter<?> create(String connectionType, DeviceInfo deviceInfo, DeviceConnection connectionConfig) {
        try {
            return new HttpConnectionAdapter(deviceInfo, connectionConfig, ioExecutor);
        } catch (Exception exception) {
            log.error("创建 HTTP 连接失败: 设备={}", deviceInfo.getDeviceId(), exception);
            throw new CollectorException("创建 HTTP 连接失败", deviceInfo.getDeviceId(), null);
        }
    }
}
