package com.wangbin.collector.core.connection.factory.provider;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.connection.adapter.ConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.WebSocketConnectionAdapter;
import com.wangbin.collector.core.connection.factory.ConnectionAdapterProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

/**
 * WebSocket 连接适配器 Provider。
 */
@Slf4j
@Component
public class WebSocketConnectionAdapterProvider implements ConnectionAdapterProvider {

    @Nullable
    private final Executor ioExecutor;
    @Nullable
    private final ScheduledExecutorService protocolScheduler;

    public WebSocketConnectionAdapterProvider(@Qualifier("ioIntensiveExecutor") @Nullable Executor ioExecutor,
                                              @Qualifier("timeSliceScheduler")
                                              @Nullable ScheduledExecutorService protocolScheduler) {
        this.ioExecutor = ioExecutor;
        this.protocolScheduler = protocolScheduler;
    }

    @Override
    public Set<String> supportedConnectionTypes() {
        return Set.of("WEBSOCKET");
    }

    @Override
    public ConnectionAdapter<?> create(String connectionType, DeviceInfo deviceInfo, DeviceConnection connectionConfig) {
        try {
            return new WebSocketConnectionAdapter(deviceInfo, connectionConfig, ioExecutor, protocolScheduler);
        } catch (Exception exception) {
            log.error("创建 WebSocket 连接失败: 设备={}", deviceInfo.getDeviceId(), exception);
            throw new CollectorException("创建 WebSocket 连接失败", deviceInfo.getDeviceId(), null);
        }
    }
}
