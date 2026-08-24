package com.wangbin.collector.core.config.protocol;

import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.collector.protocol.base.ProtocolCollector;

import java.util.List;
import java.util.Map;

/**
 * 为调度器和工厂单元测试提供轻量协议元数据。
 */
public final class ProtocolDescriptorTestProviders {

    private ProtocolDescriptorTestProviders() {
    }

    public static List<ProtocolDescriptorProvider> all() {
        return List.of(registry -> {
            registry.registerPrimary(registry.descriptor("MODBUS_TCP", "Modbus TCP",
                    "测试 Modbus TCP", List.of(), TestCollector.class, "MODBUS_TCP", 502,
                    ProtocolAddressingMode.NUMERIC, true, true, false, List.of("40001"), List.of()));
            registry.registerPrimary(registry.descriptor("BACNET_IP", "BACnet/IP",
                    "测试 BACnet/IP", List.of("BACNET", "BACNETIP", "BACNET/IP"), TestCollector.class,
                    "BACNET_IP", 47808, ProtocolAddressingMode.MIXED, true, true, true,
                    List.of("analogInput:1.presentValue"), List.of()));
        });
    }

    public static ProtocolDescriptorRegistry registry() {
        return new ProtocolDescriptorRegistry(all());
    }

    private static final class TestCollector implements ProtocolCollector {
        @Override public void init(DeviceInfo deviceInfo) throws CollectorException { }
        @Override public void connect() throws CollectorException { }
        @Override public void disconnect() throws CollectorException { }
        @Override public boolean isConnected() { return false; }
        @Override public String getConnectionStatus() { return "DISCONNECTED"; }
        @Override public String getLastError() { return null; }
        @Override public Map<String, Object> getStatistics() { return Map.of(); }
        @Override public void resetStatistics() { }
        @Override public void destroy() { }
        @Override public Map<String, Object> getDeviceStatus() throws CollectorException { return Map.of(); }
        @Override public String getCollectorType() { return "TEST"; }
        @Override public String getProtocolType() { return "TEST"; }
    }
}
