package com.wangbin.collector.core.collector.protocol.dlt645.transport;

import com.wangbin.collector.core.collector.protocol.dlt645.domain.Dlt645Address;
import com.wangbin.collector.core.connection.serial.SharedSerialChannelManager;

/**
 * 单个本地设备对应的 DL/T 645 逻辑会话。
 */
public final class Dlt645Session implements AutoCloseable {

    private final Dlt645Address meterAddress;
    private final Dlt645Bus bus;
    private final SharedSerialChannelManager.Lease lease;

    /**
     * 创建当前组件实例。
     */
    public Dlt645Session(Dlt645Address meterAddress,
                         Dlt645Bus bus,
                         SharedSerialChannelManager.Lease lease) {
        this.meterAddress = meterAddress;
        this.bus = bus;
        this.lease = lease;
    }

    /**
     * 查询并返回业务数据。
     */
    public byte[] readData(String identifier) throws Exception {
        return bus.readData(meterAddress, identifier);
    }

    /**
     * 写入或持久化业务数据。
     */
    public boolean writeData(String identifier,
                             byte[] password,
                             byte[] operatorCode,
                             byte[] value) throws Exception {
        return bus.writeData(meterAddress, identifier, password, operatorCode, value);
    }

    /**
     * 查询并返回业务数据。
     */
    public Dlt645Address readAddress() throws Exception {
        return bus.readAddress();
    }

    public boolean isOpen() {
        return bus.isOpen();
    }

    /**
     * 执行当前业务逻辑。
     */
    public String meterAddress() {
        return meterAddress.value();
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public void close() throws Exception {
        lease.close();
    }
}
