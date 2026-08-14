package com.wangbin.collector.core.connection.adapter;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.protocol.iec101.domain.Iec101LinkConfig;
import com.wangbin.collector.core.collector.protocol.iec101.transport.Iec101Bus;
import com.wangbin.collector.core.collector.protocol.iec101.transport.Iec101Session;
import com.wangbin.collector.core.connection.serial.SerialEndpoint;
import com.wangbin.collector.core.connection.serial.SharedSerialChannelManager;

/**
 * IEC60870-5-101 非平衡控制站连接适配器。
 */
public class Iec101ConnectionAdapter extends AbstractConnectionAdapter<Iec101Session> {

    private static final String SERIAL_OWNER = "IEC101";

    private final SharedSerialChannelManager serialChannelManager;
    private Iec101Session session;

    /**
     * 创建当前组件实例。
     */
    public Iec101ConnectionAdapter(DeviceInfo deviceInfo,
                                   DeviceConnection config,
                                   SharedSerialChannelManager serialChannelManager) {
        super(deviceInfo, config);
        this.serialChannelManager = serialChannelManager;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doConnect() throws Exception {
        String linkMode = config.getStringConfig("linkMode", "UNBALANCED");
        if (!"UNBALANCED".equalsIgnoreCase(linkMode)) {
            throw new IllegalArgumentException("当前 IEC101 只支持非平衡控制站模式");
        }
        SerialEndpoint endpoint = new SerialEndpoint(
                config.getStringConfig("serialPort", null),
                config.getIntConfig("baudRate", 9600),
                config.getIntConfig("dataBits", 8),
                config.getIntConfig("stopBits", 1),
                config.getStringConfig("parity", "EVEN"),
                resolveReadTimeout(),
                resolveWriteTimeout());
        Iec101LinkConfig linkConfig = new Iec101LinkConfig(
                config.getIntConfig("linkAddressSize", 1),
                config.getIntConfig("causeOfTransmissionSize", 2),
                config.getIntConfig("commonAddressSize", 2),
                config.getIntConfig("informationObjectAddressSize", 3));
        SharedSerialChannelManager.Lease lease = serialChannelManager.acquire(endpoint, SERIAL_OWNER);
        try {
            Iec101Bus bus = new Iec101Bus(
                    lease,
                    linkConfig,
                    resolveReadTimeout(),
                    config.getIntConfig("retryCount", resolveRetryCount()),
                    config.getIntConfig("interFrameDelayMs", 20));
            int linkAddress = config.getIntConfig("linkAddress", 1);
            int commonAddress = config.getIntConfig("commonAddress", 1);
            session = new Iec101Session(linkAddress, commonAddress, linkConfig, bus, lease);
            session.initialize();
            connectionParams.put("serialPort", endpoint.portName());
            connectionParams.put("baudRate", endpoint.baudRate());
            connectionParams.put("linkAddress", linkAddress);
            connectionParams.put("commonAddress", commonAddress);
        } catch (Exception exception) {
            lease.close();
            throw exception;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doDisconnect() throws Exception {
        Iec101Session current = session;
        session = null;
        if (current != null) {
            current.close();
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doHeartbeat() {
        if (session == null || !session.isOpen()) {
            throw new IllegalStateException("IEC101 串口会话不可用");
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doAuthenticate() {
        // IEC101 当前实现不包含独立认证阶段。
    }

    @Override
    public Iec101Session getClient() {
        return session;
    }

    /**
     * 解析或转换业务数据。
     */
    private int resolveReadTimeout() {
        if (config.getReadTimeout() != null && config.getReadTimeout() > 0) {
            return config.getReadTimeout();
        }
        if (config.getTimeout() != null && config.getTimeout() > 0) {
            return config.getTimeout();
        }
        return 3000;
    }

    /**
     * 解析或转换业务数据。
     */
    private int resolveWriteTimeout() {
        return config.getWriteTimeout() != null && config.getWriteTimeout() > 0
                ? config.getWriteTimeout() : resolveReadTimeout();
    }

    /**
     * 解析或转换业务数据。
     */
    private int resolveRetryCount() {
        return config.getRetries() != null && config.getRetries() >= 0 ? config.getRetries() : 2;
    }
}
