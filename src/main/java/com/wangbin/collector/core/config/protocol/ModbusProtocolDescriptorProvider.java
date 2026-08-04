package com.wangbin.collector.core.config.protocol;

import com.wangbin.collector.core.collector.protocol.modbus.Plc4xModbusRtuCollector;
import com.wangbin.collector.core.collector.protocol.modbus.Plc4xModbusTcpCollector;

import java.util.List;

/**
 * Modbus 协议元数据提供者。
 */
public class ModbusProtocolDescriptorProvider implements ProtocolDescriptorProvider {

    /**
     * 注册 Modbus TCP、Modbus RTU 及必要别名。
     *
     * @param registry 协议元数据注册表
     */
    @Override
    public void register(ProtocolDescriptorRegistry registry) {
        registry.registerPrimary(registry.descriptor("MODBUS_TCP", "Modbus TCP",
                "Modbus TCP register polling over Ethernet.",
                List.of(), Plc4xModbusTcpCollector.class, "MODBUS_TCP", 502, ProtocolAddressingMode.NUMERIC,
                true, true, false,
                List.of("40001", "HOLDING_REGISTER:1", "COIL:0"),
                registry.fields(
                        registry.field("host", "string", "Device host", true, "127.0.0.1", null, "connection"),
                        registry.field("port", "number", "Port", true, "502", null, "connection"),
                        registry.field("slaveId", "number", "Slave ID", true, "1", null, "protocol"),
                        registry.field("byteOrder", "select", "Byte order", true, "BIG_ENDIAN",
                                List.of("BIG_ENDIAN", "LITTLE_ENDIAN"), "protocol"),
                        registry.field("parity", "select", "Parity", false, "none",
                                List.of("none", "odd", "even"), "advanced"),
                        registry.field("plc4xConnectionString", "string", "PLC4X connection string", false, "", null,
                                "advanced"),
                        registry.field("pingAddress", "string", "PLC4X ping address", false, "", null, "advanced"),
                        registry.field("maxRegistersPerRequest", "number", "Max registers per request", false,
                                "125", null, "advanced"),
                        registry.field("maxCoilsPerRequest", "number", "Max coils per request", false,
                                "2000", null, "advanced"),
                        registry.field("readTimeout", "number", "Read timeout (ms)", false, "3000", null,
                                "advanced"),
                        registry.field("timeout", "number", "Protocol timeout (ms)", false, "3000", null,
                                "advanced"))));
        registry.registerPrimary(registry.descriptor("MODBUS_RTU", "Modbus RTU",
                "Modbus serial line collection.",
                List.of("MODBUS_ASCII"), Plc4xModbusRtuCollector.class, "MODBUS_RTU", null,
                ProtocolAddressingMode.NUMERIC,
                true, true, false,
                List.of("40001", "INPUT_REGISTER:0", "COIL:10"),
                registry.fields(
                        registry.field("serialPort", "string", "Serial port", true, "COM1", null, "connection"),
                        registry.field("baudRate", "number", "Baud rate", true, "9600", null, "connection"),
                        registry.field("dataBits", "number", "Data bits", true, "8", null, "connection"),
                        registry.field("stopBits", "number", "Stop bits", true, "1", null, "connection"),
                        registry.field("parity", "select", "Parity", true, "none",
                                List.of("none", "odd", "even"), "connection"),
                        registry.field("slaveId", "number", "Slave ID", true, "1", null, "protocol"),
                        registry.field("byteOrder", "select", "Byte order", true, "BIG_ENDIAN",
                                List.of("BIG_ENDIAN", "LITTLE_ENDIAN"), "protocol"),
                        registry.field("interFrameDelay", "number", "Inter-frame delay (ms)", true, "5", null,
                                "advanced"),
                        registry.field("plc4xProtocolCode", "select", "PLC4X protocol code", false, "modbus-rtu",
                                List.of("modbus-rtu", "modbus-ascii"), "advanced"),
                        registry.field("plc4xConnectionString", "string", "PLC4X connection string", false, "", null,
                                "advanced"),
                        registry.field("maxRegistersPerRequest", "number", "Max registers per request", false,
                                "125", null, "advanced"),
                        registry.field("maxCoilsPerRequest", "number", "Max coils per request", false,
                                "2000", null, "advanced"),
                        registry.field("readTimeout", "number", "Read timeout (ms)", false, "3000", null,
                                "advanced"),
                        registry.field("timeout", "number", "Protocol timeout (ms)", false, "3000", null,
                                "advanced"))));
    }
}
