package com.wangbin.collector.core.connection.adapter;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.protocol.dlt645.domain.Dlt645Address;
import com.wangbin.collector.core.collector.protocol.dlt645.transport.Dlt645Bus;
import com.wangbin.collector.core.collector.protocol.dlt645.transport.Dlt645Session;
import com.wangbin.collector.core.connection.serial.SerialEndpoint;
import com.wangbin.collector.core.connection.serial.SharedSerialChannelManager;

/**
 * DL/T 645-2007 连接适配器。
 */
public class Dlt645ConnectionAdapter extends AbstractConnectionAdapter<Dlt645Session> {

    private static final String SERIAL_OWNER = "DLT645_2007";

    private final SharedSerialChannelManager serialChannelManager;
    private Dlt645Session session;

    /**
     * 创建当前组件实例。
     */
    public Dlt645ConnectionAdapter(DeviceInfo deviceInfo,
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
        SerialEndpoint endpoint = new SerialEndpoint(
                config.getStringConfig("serialPort", null),
                config.getIntConfig("baudRate", 2400),
                config.getIntConfig("dataBits", 8),
                config.getIntConfig("stopBits", 1),
                config.getStringConfig("parity", "EVEN"),
                resolveReadTimeout(),
                resolveWriteTimeout());
        SharedSerialChannelManager.Lease lease = serialChannelManager.acquire(endpoint, SERIAL_OWNER);
        try {
            Dlt645Bus bus = new Dlt645Bus(
                    lease,
                    resolveReadTimeout(),
                    config.getIntConfig("retryCount", resolveRetryCount()),
                    config.getIntConfig("wakeupByteCount", 4),
                    config.getIntConfig("interFrameDelayMs", 20));
            Dlt645Address meterAddress = new Dlt645Address(config.getStringConfig("meterAddress", null));
            session = new Dlt645Session(meterAddress, bus, lease);
            connectionParams.put("serialPort", endpoint.portName());
            connectionParams.put("baudRate", endpoint.baudRate());
            connectionParams.put("meterAddress", meterAddress.value());
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
        Dlt645Session current = session;
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
            throw new IllegalStateException("DL/T 645 串口会话不可用");
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doAuthenticate() {
        // 写操作的密码认证由具体写命令携带，连接阶段不执行独立认证。
    }

    @Override
    public Dlt645Session getClient() {
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
