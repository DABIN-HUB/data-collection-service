package com.wangbin.collector.core.connection.factory.provider;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.connection.adapter.BacnetIpConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.BacnetMstpConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.BacnetScConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.ConnectionAdapter;
import com.wangbin.collector.core.connection.factory.ConnectionAdapterProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

/**
 * BACnet 协议族连接适配器 Provider。
 */
@Slf4j
@Component
public class BacnetConnectionAdapterProvider implements ConnectionAdapterProvider {

    @Nullable
    private final Executor ioExecutor;
    @Nullable
    private final ScheduledExecutorService protocolScheduler;

    public BacnetConnectionAdapterProvider(@Qualifier("ioIntensiveExecutor") @Nullable Executor ioExecutor,
                                           @Qualifier("timeSliceScheduler")
                                           @Nullable ScheduledExecutorService protocolScheduler) {
        this.ioExecutor = ioExecutor;
        this.protocolScheduler = protocolScheduler;
    }

    @Override
    public Set<String> supportedConnectionTypes() {
        return Set.of("BACNET_IP", "BACNET_MSTP", "BACNET_SC");
    }

    @Override
    public ConnectionAdapter<?> create(String connectionType, DeviceInfo deviceInfo, DeviceConnection connectionConfig) {
        try {
            return switch (connectionType) {
                case "BACNET_IP" -> new BacnetIpConnectionAdapter(deviceInfo, connectionConfig, protocolScheduler);
                case "BACNET_MSTP" -> new BacnetMstpConnectionAdapter(deviceInfo, connectionConfig);
                case "BACNET_SC" -> new BacnetScConnectionAdapter(deviceInfo, connectionConfig, ioExecutor,
                        protocolScheduler);
                default -> throw new IllegalArgumentException("BACnet Provider 不支持连接类型: " + connectionType);
            };
        } catch (Exception exception) {
            log.error("创建 {} 连接失败: {}", connectionType, deviceInfo.getDeviceId(), exception);
            throw switch (connectionType) {
                case "BACNET_IP" -> new CollectorException("Create BACnet/IP connection failed",
                        deviceInfo.getDeviceId(), null);
                case "BACNET_MSTP" -> new CollectorException("Create BACnet MS/TP connection failed",
                        deviceInfo.getDeviceId(), null);
                case "BACNET_SC" -> new CollectorException("Create BACnet/SC connection failed",
                        deviceInfo.getDeviceId(), null);
                default -> new CollectorException("不支持的连接类型: " + connectionType,
                        deviceInfo.getDeviceId(), null);
            };
        }
    }
}
