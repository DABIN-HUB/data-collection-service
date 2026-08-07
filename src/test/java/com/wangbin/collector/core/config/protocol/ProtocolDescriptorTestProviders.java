package com.wangbin.collector.core.config.protocol;

import java.util.List;

/**
 * 测试中显式装配所有协议元数据 Provider，避免恢复 Registry 的生产 fallback。
 */
public final class ProtocolDescriptorTestProviders {

    private ProtocolDescriptorTestProviders() {
    }

    public static List<ProtocolDescriptorProvider> all() {
        return List.of(
                new ModbusProtocolDescriptorProvider(),
                new S7ProtocolDescriptorProvider(),
                new OpcUaProtocolDescriptorProvider(),
                new BacnetProtocolDescriptorProvider(),
                new MitsubishiProtocolDescriptorProvider(),
                new OmronProtocolDescriptorProvider(),
                new EtherNetIpProtocolDescriptorProvider(),
                new AdsProtocolDescriptorProvider(),
                new KnxProtocolDescriptorProvider(),
                new OpcDaProtocolDescriptorProvider(),
                new SnmpProtocolDescriptorProvider(),
                new CoapProtocolDescriptorProvider(),
                new MqttProtocolDescriptorProvider(),
                new IecProtocolDescriptorProvider(),
                new Dlt645ProtocolDescriptorProvider(),
                new Iec101ProtocolDescriptorProvider(),
                new Iec61850ProtocolDescriptorProvider(),
                new HttpProtocolDescriptorProvider(),
                new WebSocketProtocolDescriptorProvider(),
                new CustomProtocolDescriptorProvider()
        );
    }

    public static ProtocolDescriptorRegistry registry() {
        return new ProtocolDescriptorRegistry(all());
    }
}
