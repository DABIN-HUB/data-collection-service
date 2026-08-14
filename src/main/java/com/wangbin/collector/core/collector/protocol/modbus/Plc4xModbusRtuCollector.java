package com.wangbin.collector.core.collector.protocol.modbus;


import com.wangbin.collector.common.constant.CommonMapKeys;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.enums.DataType;
import com.wangbin.collector.common.enums.Parity;
import com.wangbin.collector.core.collector.protocol.modbus.base.AbstractModbusCollector;
import com.wangbin.collector.core.collector.protocol.modbus.base.ModbusTransport;
import com.wangbin.collector.core.collector.protocol.modbus.domain.ModbusAddress;
import com.wangbin.collector.core.collector.protocol.modbus.domain.RegisterType;
import com.wangbin.collector.core.collector.protocol.modbus.plan.ModbusReadPlan;
import com.wangbin.collector.core.collector.protocol.modbus.utils.ModbusUtils;
import com.wangbin.collector.core.connection.adapter.Plc4xModbusRtuConnectionAdapter;
import lombok.extern.slf4j.Slf4j;
import org.apache.plc4x.java.api.messages.PlcReadResponse;
import org.apache.plc4x.java.api.messages.PlcTagResponse;
import org.apache.plc4x.java.api.messages.PlcWriteResponse;
import org.apache.plc4x.java.api.types.PlcResponseCode;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 基于 PLC4X 的 Modbus RTU 采集器，复用现有批量计划和数据处理流程。
 */
@Slf4j
@Component
public class Plc4xModbusRtuCollector extends AbstractModbusCollector {

    private static final String FIELD_NAME = "value";

    private Plc4xModbusRtuConnectionAdapter connectionAdapter;
    private String serialPort;
    private int baudRate;
    private int dataBits;
    private int stopBits;
    private Parity parity = Parity.none;
    private int slaveId = 1;
    private ByteOrder byteOrder = ByteOrder.BIG_ENDIAN;
    private int interFrameDelay = 5;

    private final ModbusTransport transport = new ModbusTransport() {
        /**
         * 查询并返回业务数据。
         */
        @Override
        public byte[] read(int unitId, RegisterType registerType, int startAddress, int quantity) throws Exception {
            PlcReadResponse response = await(requireConnection().getClient()
                    .readRequestBuilder()
                    .addTagAddress(FIELD_NAME, Plc4xModbusTagBuilder.build(
                            registerType, startAddress, quantity, unitId))
                    .build()
                    .execute());
            ensureResponseOk(response, FIELD_NAME, "read");

            return switch (registerType) {
                case COIL, DISCRETE_INPUT -> Plc4xModbusValueExtractor.coilBytes(
                        response, FIELD_NAME, quantity, parity);
                case HOLDING_REGISTER, INPUT_REGISTER -> Plc4xModbusValueExtractor.registerBytes(
                        response, FIELD_NAME, quantity);
            };
        }

        /**
         * 写入或持久化业务数据。
         */
        @Override
        public boolean writeMultipleCoils(
                int unitId, int startAddress, int quantity, byte[] coilBytes) throws Exception {
            Object[] values = ModbusUtils.getCoilValues(coilBytes, quantity, parity).toArray();
            PlcWriteResponse response = await(requireConnection().getClient()
                    .writeRequestBuilder()
                    .addTagAddress(
                            FIELD_NAME,
                            Plc4xModbusTagBuilder.build(RegisterType.COIL, startAddress, quantity, unitId),
                            values)
                    .build()
                    .execute());
            ensureResponseOk(response, FIELD_NAME, "write");
            return true;
        }

        /**
         * 写入或持久化业务数据。
         */
        @Override
        public boolean writeMultipleRegisters(int unitId, int startAddress, short[] registers) throws Exception {
            PlcWriteResponse response = await(requireConnection().getClient()
                    .writeRequestBuilder()
                    .addTagAddress(
                            FIELD_NAME,
                            Plc4xModbusTagBuilder.build(
                                    RegisterType.HOLDING_REGISTER, startAddress, registers.length, unitId),
                            toRegisterWriteValues(registers))
                    .build()
                    .execute());
            ensureResponseOk(response, FIELD_NAME, "write");
            return true;
        }
    };

    @Override
    public String getCollectorType() {
        return "ModbusRTU";
    }

    @Override
    public String getProtocolType() {
        if (deviceInfo != null && "MODBUS_ASCII".equalsIgnoreCase(deviceInfo.getProtocolType())) {
            return "MODBUS_ASCII";
        }
        return "MODBUS_RTU";
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doConnect() throws Exception {
        log.info("Starting PLC4X Modbus 串口 连接:{}", deviceInfo.getDeviceId());
        DeviceConnection connectionConfig = requireConnectionConfig();

        interFrameDelay = connectionConfig.getInt("interFrameDelay", 5);
        serialPort = connectionConfig.getString("serialPort",
                connectionConfig.getHost() != null ? connectionConfig.getHost() : "COM1");
        baudRate = connectionConfig.getInt("baudRate", 9600);
        dataBits = connectionConfig.getInt("dataBits", 8);
        stopBits = connectionConfig.getInt("stopBits", 1);
        byteOrder = ModbusUtils.parseByteOrder(connectionConfig.getString("byteOrder", "BIG_ENDIAN"));
        String parityName = connectionConfig.getString("parity", Parity.none.name());
        parity = Parity.fromName(parityName != null ? parityName.toLowerCase() : Parity.none.name());
        Integer readTimeout = connectionConfig.getReadTimeout();
        timeout = readTimeout != null && readTimeout > 0 ? readTimeout : connectionConfig.getTimeout();
        if (timeout <= 0) {
            timeout = 3000;
        }
        slaveId = connectionConfig.getInt("slaveId", 1);

        this.connectionAdapter = createAndConnectAdapter(
                connectionConfig,
                Plc4xModbusRtuConnectionAdapter.class,
                "PLC4X Modbus Serial");

        log.info("PLC4X Modbus 串口 已连接:端口={} baud={} dataBits={} stopBits={} parity={}",
                serialPort, baudRate, dataBits, stopBits, parity.name());
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doDisconnect() throws Exception {
        removeConnectionSilently();
        registerCache.clear();
        log.info("PLC4X Modbus 串口 已断开");
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Object doReadPoint(DataPoint point) throws Exception {
        String address = point.getAddress();
        if (address == null || address.isEmpty()) {
            throw new IllegalArgumentException("Point address cannot be empty");
        }

        ModbusAddress modbusAddress = parseModbusAddress(address);
        int unitId = sanitizeUnitId(resolveUnitId(point));
        int registerCount = resolveQuantity(modbusAddress.getRegisterType(), point.getDataType());
        byte[] raw = transport.read(unitId, modbusAddress.getRegisterType(), modbusAddress.getAddress(), registerCount);

        return switch (modbusAddress.getRegisterType()) {
            case COIL, DISCRETE_INPUT -> ModbusUtils.parseCoilValue(raw, 0, parity);
            case HOLDING_REGISTER, INPUT_REGISTER ->
                    ModbusUtils.parseRegisterValue(raw, point.getDataType(), byteOrder);
        };
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected boolean doWritePoint(DataPoint point, Object value) throws Exception {
        String address = point.getAddress();
        if (address == null || address.isEmpty()) {
            throw new IllegalArgumentException("Point address cannot be empty");
        }

        ModbusAddress modbusAddress = parseModbusAddress(address);
        int unitId = sanitizeUnitId(resolveUnitId(point));

        return switch (modbusAddress.getRegisterType()) {
            case COIL -> transport.writeMultipleCoils(
                    unitId,
                    modbusAddress.getAddress(),
                    1,
                    ModbusUtils.buildCoilBytes(List.of(toBoolean(value)), parity));
            case HOLDING_REGISTER -> transport.writeMultipleRegisters(
                    unitId,
                    modbusAddress.getAddress(),
                    ModbusUtils.valueToRegisters(value, point.getDataType(), byteOrder));
            default -> throw new IllegalArgumentException(
                    "This register type does not support write: " + modbusAddress.getRegisterType());
        };
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Map<String, Object> doGetDeviceStatus() {
        Map<String, Object> status = getBaseDeviceStatus(getProtocolType());
        status.put("serialPort", serialPort);
        status.put("baudRate", baudRate);
        status.put("dataBits", dataBits);
        status.put("stopBits", stopBits);
        status.put("parity", parity.name());
        status.put("slaveId", slaveId);
        status.put(CommonMapKeys.TIMEOUT, timeout);
        status.put("byteOrder", byteOrder.toString());
        status.put("interFrameDelay", interFrameDelay);
        status.put(CommonMapKeys.DRIVER, "PLC4X");
        status.put("connectionString", connectionAdapter != null ? connectionAdapter.getConnectionString() : null);

        try {
            status.put(CommonMapKeys.DEVICE_CONNECTED, testConnection(slaveId));
        } catch (Exception e) {
            status.put(CommonMapKeys.DEVICE_CONNECTED, false);
            status.put("connectionError", e.getMessage());
        }
        return status;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Object doExecuteCommand(int unitId, String command, Map<String, Object> params) throws Exception {
        Map<String, Object> safeParams = params != null ? params : Map.of();
        int targetUnitId = safeParams.containsKey("slaveId") ? sanitizeUnitId(unitId) : slaveId;

        return switch (command.toUpperCase()) {
            case "READ_MULTIPLE_REGISTERS" -> executeReadMultipleRegisters(targetUnitId, safeParams);
            case "WRITE_MULTIPLE_REGISTERS" -> executeWriteMultipleRegisters(targetUnitId, safeParams);
            case "READ_COILS" -> executeReadCoils(targetUnitId, safeParams);
            case "WRITE_COILS" -> executeWriteCoils(targetUnitId, safeParams);
            case "DIAGNOSTIC" -> executeDiagnostic(targetUnitId);
            case "READ_EXCEPTION_STATUS", "DIAGNOSTICS", "GET_COMM_EVENT_COUNTER", "GET_COMM_EVENT_LOG" ->
                    throw unsupportedSerialCommand(command);
            default -> throw new IllegalArgumentException("Unsupported Modbus command: " + command);
        };
    }

    /**
     * 处理当前业务流程。
     */
    private Object executeReadMultipleRegisters(int unitId, Map<String, Object> params) throws Exception {
        int address = toInt(params.getOrDefault("address", 0), 0);
        int quantity = toInt(params.getOrDefault("quantity", 1), 1);
        byte[] raw = transport.read(unitId, RegisterType.HOLDING_REGISTER, address, quantity);
        ByteBuffer buffer = ByteBuffer.wrap(raw).order(byteOrder);
        List<Integer> values = new ArrayList<>(quantity);
        for (int i = 0; i < quantity; i++) {
            values.add(buffer.getShort() & 0xFFFF);
        }

        Map<String, Object> result = new HashMap<>();
        result.put(CommonMapKeys.SUCCESS, true);
        result.put(CommonMapKeys.ADDRESS, address);
        result.put(CommonMapKeys.QUANTITY, quantity);
        result.put("values", values);
        return result;
    }

    /**
     * 处理当前业务流程。
     */
    private Object executeWriteMultipleRegisters(int unitId, Map<String, Object> params) throws Exception {
        int address = toInt(params.getOrDefault("address", 0), 0);
        List<?> values = (List<?>) params.get("values");
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("values cannot be empty");
        }

        short[] registers = new short[values.size()];
        for (int i = 0; i < values.size(); i++) {
            registers[i] = (short) toInt(values.get(i), 0);
        }
        boolean success = transport.writeMultipleRegisters(unitId, address, registers);

        Map<String, Object> result = new HashMap<>();
        result.put(CommonMapKeys.SUCCESS, success);
        result.put(CommonMapKeys.ADDRESS, address);
        result.put(CommonMapKeys.QUANTITY, values.size());
        return result;
    }

    /**
     * 处理当前业务流程。
     */
    private Object executeReadCoils(int unitId, Map<String, Object> params) throws Exception {
        int address = toInt(params.getOrDefault("address", 0), 0);
        int quantity = toInt(params.getOrDefault("quantity", 1), 1);
        byte[] raw = transport.read(unitId, RegisterType.COIL, address, quantity);
        List<Boolean> values = ModbusUtils.getCoilValues(raw, quantity, parity);

        Map<String, Object> result = new HashMap<>();
        result.put(CommonMapKeys.SUCCESS, true);
        result.put(CommonMapKeys.ADDRESS, address);
        result.put(CommonMapKeys.QUANTITY, quantity);
        result.put("values", values);
        return result;
    }

    /**
     * 处理当前业务流程。
     */
    private Object executeWriteCoils(int unitId, Map<String, Object> params) throws Exception {
        int address = toInt(params.getOrDefault("address", 0), 0);
        List<?> values = (List<?>) params.get("values");
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("values cannot be empty");
        }

        List<Boolean> coilValues = new ArrayList<>(values.size());
        for (Object value : values) {
            coilValues.add(toBoolean(value));
        }
        boolean success = transport.writeMultipleCoils(
                unitId,
                address,
                coilValues.size(),
                ModbusUtils.buildCoilBytes(coilValues, parity));

        Map<String, Object> result = new HashMap<>();
        result.put(CommonMapKeys.SUCCESS, success);
        result.put(CommonMapKeys.ADDRESS, address);
        result.put(CommonMapKeys.QUANTITY, coilValues.size());
        return result;
    }

    /**
     * 执行当前业务逻辑。
     */
    private UnsupportedOperationException unsupportedSerialCommand(String command) {
        return new UnsupportedOperationException(
                "PLC4X Modbus RTU串口连接不支持直接执行功能码命令: " + command);
    }

    /**
     * 处理当前业务流程。
     */
    private Object executeDiagnostic(int unitId) {
        Map<String, Object> result = new HashMap<>();
        result.put(CommonMapKeys.PROTOCOL, getProtocolType());
        result.put("serialPort", serialPort);
        result.put("baudRate", baudRate);
        result.put("dataBits", dataBits);
        result.put("stopBits", stopBits);
        result.put("parity", parity.name());
        result.put("slaveId", unitId);
        result.put(CommonMapKeys.TIMEOUT, timeout);
        result.put("byteOrder", byteOrder.toString());
        result.put("clientConnected", isConnected());
        result.put("interFrameDelay", interFrameDelay);
        result.put(CommonMapKeys.TIMESTAMP, System.currentTimeMillis());

        try {
            boolean connected = testConnection(unitId);
            result.put(CommonMapKeys.DEVICE_CONNECTED, connected);
            result.put(CommonMapKeys.CONNECTION_TEST, connected ? "SUCCESS" : "FAILED");
        } catch (Exception e) {
            result.put(CommonMapKeys.DEVICE_CONNECTED, false);
            result.put(CommonMapKeys.CONNECTION_TEST, "FAILED");
            result.put(CommonMapKeys.ERROR, e.getMessage());
        }
        return result;
    }

    /**
     * 执行当前业务逻辑。
     */
    private int sanitizeUnitId(Integer unitIdValue) {
        return unitIdValue != null && unitIdValue > 0 ? unitIdValue : slaveId;
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean testConnection(int unitId) throws Exception {
        transport.read(unitId, RegisterType.HOLDING_REGISTER, 0, 1);
        return true;
    }

    /**
     * 解析或转换业务数据。
     */
    private int resolveQuantity(RegisterType registerType, String dataType) {
        return switch (registerType) {
            case COIL, DISCRETE_INPUT -> 1;
            case HOLDING_REGISTER, INPUT_REGISTER -> DataType.fromString(dataType).getRegisterCount();
        };
    }

    /**
     * 解析或转换业务数据。
     */
    private Object[] toRegisterWriteValues(short[] registers) {
        Object[] values = new Object[registers.length];
        for (int i = 0; i < registers.length; i++) {
            values[i] = registers[i] & 0xFFFF;
        }
        return values;
    }

    /**
     * 解析或转换业务数据。
     */
    private boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    /**
     * 校验业务条件和参数边界。
     */
    private void ensureResponseOk(PlcTagResponse response, String fieldName, String operation) {
        if (response == null) {
            throw new IllegalStateException("PLC4X " + operation + " returned null response");
        }
        PlcResponseCode code = response.getResponseCode(fieldName);
        if (code != PlcResponseCode.OK) {
            throw new IllegalStateException("PLC4X " + operation + " failed with response code: " + code);
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private int toInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    /**
     * 执行当前业务逻辑。
     */
    private <T> T await(CompletableFuture<? extends T> future) throws Exception {
        T result = future.get(timeout, TimeUnit.MILLISECONDS);
        if (interFrameDelay > 0) {
            Thread.sleep(interFrameDelay);
        }
        return result;
    }

    /**
     * 校验业务条件和参数边界。
     */
    private Plc4xModbusRtuConnectionAdapter requireConnection() {
        if (connectionAdapter == null) {
            throw new IllegalStateException("PLC4X Modbus serial connection has not been established");
        }
        return connectionAdapter;
    }

    /**
     * 执行当前业务逻辑。
     */
    private List<Integer> collectUnitIds() {
        Set<Integer> unitIds = new LinkedHashSet<>();
        for (ModbusReadPlan plan : readPlans) {
            unitIds.add(plan.getUnitId());
        }
        if (unitIds.isEmpty()) {
            unitIds.add(slaveId);
        }
        return new ArrayList<>(unitIds);
    }

    /**
     * 清理或删除业务数据。
     */
    private void removeConnectionSilently() {
        removeManagedConnection("PLC4X Modbus Serial");
        connectionAdapter = null;
    }

    /**
     * 解析或转换业务数据。
     */
    @Override
    protected int resolveBatchUnitId(DataPoint point) {
        return sanitizeUnitId(resolveUnitId(point));
    }

    @Override
    protected ModbusTransport getModbusTransport() {
        return transport;
    }

    @Override
    protected ByteOrder getModbusByteOrder() {
        return byteOrder;
    }

    @Override
    protected Parity getModbusParity() {
        return parity;
    }

    @Override
    public boolean isConnected() {
        return connectionAdapter != null && connectionAdapter.isConnected();
    }
}
