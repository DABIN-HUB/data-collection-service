package com.wangbin.collector.core.connection.adapter;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertSame;

public class HttpConnectionAdapterTest {

    @Test
    void httpConnectionAdapterShouldUseInjectedExecutorWhenPresent() {
        Executor injected = Runnable::run;
        HttpConnectionAdapter adapter = new HttpConnectionAdapter(device(), connection(), injected);

        assertSame(injected, adapter.resolveHttpExecutor());
    }

    private DeviceInfo device() {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("dev-http");
        device.setProtocolType("HTTP");
        return device;
    }

    private DeviceConnection connection() {
        DeviceConnection connection = new DeviceConnection();
        connection.setConnectionType("HTTP");
        connection.setHost("127.0.0.1");
        connection.setPort(8080);
        return connection;
    }
}
