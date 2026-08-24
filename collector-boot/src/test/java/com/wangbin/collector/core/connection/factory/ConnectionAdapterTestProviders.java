package com.wangbin.collector.core.connection.factory;

import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.config.protocol.ProtocolDescriptorTestProviders;
import com.wangbin.collector.core.config.validator.ProtocolConnectionValidator;
import com.wangbin.collector.core.connection.factory.provider.AdsConnectionAdapterProvider;
import com.wangbin.collector.core.connection.factory.provider.BacnetConnectionAdapterProvider;
import com.wangbin.collector.core.connection.factory.provider.CoapConnectionAdapterProvider;
import com.wangbin.collector.core.connection.factory.provider.CustomConnectionAdapterProvider;
import com.wangbin.collector.core.connection.factory.provider.Dlt645ConnectionAdapterProvider;
import com.wangbin.collector.core.connection.factory.provider.EtherNetIpConnectionAdapterProvider;
import com.wangbin.collector.core.connection.factory.provider.GenericConnectionAdapterProvider;
import com.wangbin.collector.core.connection.factory.provider.HttpConnectionAdapterProvider;
import com.wangbin.collector.core.connection.factory.provider.Iec101ConnectionAdapterProvider;
import com.wangbin.collector.core.connection.factory.provider.IecConnectionAdapterProvider;
import com.wangbin.collector.core.connection.factory.provider.KnxConnectionAdapterProvider;
import com.wangbin.collector.core.connection.factory.provider.MitsubishiConnectionAdapterProvider;
import com.wangbin.collector.core.connection.factory.provider.ModbusConnectionAdapterProvider;
import com.wangbin.collector.core.connection.factory.provider.MqttConnectionAdapterProvider;
import com.wangbin.collector.core.connection.factory.provider.OmronConnectionAdapterProvider;
import com.wangbin.collector.core.connection.factory.provider.OpcUaConnectionAdapterProvider;
import com.wangbin.collector.core.connection.factory.provider.S7ConnectionAdapterProvider;
import com.wangbin.collector.core.connection.factory.provider.SnmpConnectionAdapterProvider;
import com.wangbin.collector.core.connection.factory.provider.WebSocketConnectionAdapterProvider;
import com.wangbin.collector.core.connection.serial.SharedSerialChannelManager;

import java.util.List;

/**
 * 测试显式装配所有连接适配器提供者，避免恢复生产硬编码兜底逻辑。
 */
public final class ConnectionAdapterTestProviders {

    private ConnectionAdapterTestProviders() {
    }

    public static List<ConnectionAdapterProvider> all() {
        CollectorProperties collectorProperties = new CollectorProperties();
        SharedSerialChannelManager serialChannelManager = new SharedSerialChannelManager();
        return List.of(
                new GenericConnectionAdapterProvider(),
                new HttpConnectionAdapterProvider(null),
                new MqttConnectionAdapterProvider(collectorProperties),
                new WebSocketConnectionAdapterProvider(null, null),
                new CoapConnectionAdapterProvider(),
                new S7ConnectionAdapterProvider(),
                new BacnetConnectionAdapterProvider(null, null),
                new MitsubishiConnectionAdapterProvider(),
                new OmronConnectionAdapterProvider(),
                new EtherNetIpConnectionAdapterProvider(),
                new AdsConnectionAdapterProvider(),
                new KnxConnectionAdapterProvider(),
                new ModbusConnectionAdapterProvider(),
                new SnmpConnectionAdapterProvider(),
                new OpcUaConnectionAdapterProvider(),
                new IecConnectionAdapterProvider(),
                new Iec101ConnectionAdapterProvider(serialChannelManager),
                new Dlt645ConnectionAdapterProvider(serialChannelManager),
                new CustomConnectionAdapterProvider()
        );
    }

    public static ConnectionFactory factory() {
        return new ConnectionFactory(ProtocolDescriptorTestProviders.registry(),
                new ProtocolConnectionValidator(), all());
    }
}
