package com.wangbin.collector.core.collector.protocol.modbus.base;

import com.wangbin.collector.core.collector.protocol.modbus.domain.RegisterType;

/**
 * Modbus transport-specific operations used by the shared collector executor.
 */
public interface ModbusTransport {

    byte[] read(int unitId, RegisterType registerType, int startAddress, int quantity) throws Exception;

    boolean writeMultipleCoils(int unitId, int startAddress, int quantity, byte[] coilBytes) throws Exception;

    boolean writeMultipleRegisters(int unitId, int startAddress, short[] registers) throws Exception;
}
