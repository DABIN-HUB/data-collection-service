package com.wangbin.collector.core.collector.protocol.modbus.base;

import com.wangbin.collector.core.collector.protocol.modbus.domain.RegisterType;

/**
 * 定义 Modbus 不同传输方式需要提供的读写操作，供共享采集器执行模板调用。
 */
public interface ModbusTransport {

    /**
     * 查询并返回业务数据。
     */
    byte[] read(int unitId, RegisterType registerType, int startAddress, int quantity) throws Exception;

    /**
     * 写入或持久化业务数据。
     */
    boolean writeMultipleCoils(int unitId, int startAddress, int quantity, byte[] coilBytes) throws Exception;

    /**
     * 写入或持久化业务数据。
     */
    boolean writeMultipleRegisters(int unitId, int startAddress, short[] registers) throws Exception;
}
