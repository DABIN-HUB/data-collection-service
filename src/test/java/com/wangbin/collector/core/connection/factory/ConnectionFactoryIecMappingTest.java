package com.wangbin.collector.core.connection.factory;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.config.protocol.ProtocolDescriptorRegistry;
import com.wangbin.collector.core.connection.adapter.Iec104ConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.Iec61850ConnectionAdapter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ConnectionFactoryIecMappingTest {

    @Test
    void shouldCreateIec104ConnectionAdapter() {
        ConnectionFactory factory = new ConnectionFactory(new ProtocolDescriptorRegistry());
        DeviceInfo deviceInfo = new DeviceInfo();
        deviceInfo.setDeviceId("dev-iec104");
        deviceInfo.setProtocolType("IEC104");
        deviceInfo.setIpAddress("127.0.0.1");
        deviceInfo.setPort(2404);

        DeviceConnection config = new DeviceConnection();
        config.setConnectionType("IEC104");
        config.setHost("127.0.0.1");
        config.setPort(2404);

        assertInstanceOf(Iec104ConnectionAdapter.class, factory.createConnection(deviceInfo, config));
    }

    @Test
    void shouldCreateIec61850ConnectionAdapter() {
        ConnectionFactory factory = new ConnectionFactory(new ProtocolDescriptorRegistry());
        DeviceInfo deviceInfo = new DeviceInfo();
        deviceInfo.setDeviceId("dev-iec61850");
        deviceInfo.setProtocolType("IEC61850");
        deviceInfo.setIpAddress("127.0.0.1");
        deviceInfo.setPort(102);

        DeviceConnection config = new DeviceConnection();
        config.setConnectionType("IEC61850");
        config.setHost("127.0.0.1");
        config.setPort(102);

        assertInstanceOf(Iec61850ConnectionAdapter.class, factory.createConnection(deviceInfo, config));
    }
}
