package com.wangbin.collector.core.collector.protocol.modbus;

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
import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.connection.adapter.Plc4xModbusTcpConnectionAdapter;
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
 * 基于 PLC4X 的 Modbus TCP 采集器，复用现有批量计划和数据处理流程。
 */
@Slf4j
@Component
public class Plc4xModbusTcpCollector extends AbstractModbusCollector {

    private static final String FIELD_NAME = "value";

    private Plc4xModbusTcpConnectionAdapter connectionAdapter;
    private ByteOrder byteOrder = ByteOrder.BIG_ENDIAN;
    private Parity parity = Parity.none;

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
        return "ModbusTCP";
    }

    @Override
    public String getProtocolType() {
        return "MODBUS_TCP";
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doConnect() throws Exception {
        log.info("Starting PLC4X Modbus TCP 连接:{}", deviceInfo.getDeviceId());
        DeviceConnection desiredConfig = requireConnectionConfig();
        this.connectionAdapter = createAndConnectAdapter(
                desiredConfig,
                Plc4xModbusTcpConnectionAdapter.class,
                "PLC4X Modbus TCP");

        DeviceConnection connectionConfig = getCurrentConnectionConfig();
        if (connectionConfig == null) {
            connectionConfig = desiredConfig;
        }

        this.timeout = connectionConfig.getReadTimeout() != null
                ? connectionConfig.getReadTimeout()
                : connectionConfig.getTimeout();
        if (this.timeout <= 0) {
            CollectorProperties.ModbusConfig defaults = collectorProperties != null
                    ? collectorProperties.getModbus()
                    : new CollectorProperties.ModbusConfig();
            this.timeout = defaults.getTimeout();
        }

        byteOrder = ModbusUtils.parseByteOrder(connectionConfig.getString("byteOrder", "BIG_ENDIAN"));
        String parityName = connectionConfig.getString("parity", Parity.none.name());
        parity = Parity.fromName(parityName != null ? parityName.toLowerCase() : Parity.none.name());

        log.info("PLC4X Modbus TCP 已连接:{}:{} byteOrder={} parity={}",
                connectionConfig.getHost(),
                connectionConfig.getPort(),
                byteOrder,
                parity.name());
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doDisconnect() throws Exception {
        removeConnectionSilently();
        registerCache.clear();
        log.info("PLC4X Modbus TCP 已断开");
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
        int unitId = resolveUnitId(point);
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
        int unitId = resolveUnitId(point);

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
        Map<String, Object> status = getBaseDeviceStatus("Modbus TCP");
        status.put("unitIds", collectUnitIds());
        status.put("byteOrder", byteOrder.toString());
        status.put("parity", parity.name());
        status.put("driver", "PLC4X");
        status.put("connectionString", connectionAdapter != null ? connectionAdapter.getConnectionString() : null);

        try {
            status.put("deviceConnected", testConnection());
        } catch (Exception e) {
            status.put("deviceConnected", false);
            status.put("connectionError", e.getMessage());
        }

        return status;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Object doExecuteCommand(int unitId, String command, Map<String, Object> params) throws Exception {
        return switch (command.toUpperCase()) {
            case "READ_MULTIPLE_REGISTERS" -> executeReadMultipleRegisters(unitId, params);
            case "WRITE_MULTIPLE_REGISTERS" -> executeWriteMultipleRegisters(unitId, params);
            case "READ_COILS" -> executeReadCoils(unitId, params);
            case "WRITE_COILS" -> executeWriteCoils(unitId, params);
            case "DIAGNOSTIC" -> executeDiagnostic(unitId);
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
        result.put("success", true);
        result.put("address", address);
        result.put("quantity", quantity);
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
        result.put("success", success);
        result.put("address", address);
        result.put("quantity", values.size());
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
        result.put("success", true);
        result.put("address", address);
        result.put("quantity", quantity);
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
        result.put("success", success);
        result.put("address", address);
        result.put("quantity", coilValues.size());
        return result;
    }

    /**
     * 处理当前业务流程。
     */
    private Object executeDiagnostic(int unitId) {
        Map<String, Object> result = new HashMap<>();
        result.put("protocol", "Modbus TCP");
        result.put("unitId", unitId);
        result.put("timeout", timeout);
        result.put("masterConnected", connectionAdapter != null && connectionAdapter.isConnected());
        result.put("timestamp", System.currentTimeMillis());

        try {
            boolean connected = testConnection();
            result.put("deviceConnected", connected);
            result.put("connectionTest", connected ? "SUCCESS" : "FAILED");
        } catch (Exception e) {
            result.put("deviceConnected", false);
            result.put("connectionTest", "FAILED");
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean testConnection() throws Exception {
        int unitId = resolveHealthCheckUnitId();
        transport.read(unitId, RegisterType.HOLDING_REGISTER, 0, 1);
        return true;
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
            Integer configured = getConfiguredSlaveId();
            if (configured != null) {
                unitIds.add(configured);
            }
        }
        if (unitIds.isEmpty()) {
            unitIds.add(1);
        }
        return new ArrayList<>(unitIds);
    }

    private Integer getConfiguredSlaveId() {
        DeviceConnection connection = getCurrentConnectionConfig();
        return connection != null ? connection.getInt("slaveId", 1) : null;
    }

    /**
     * 解析或转换业务数据。
     */
    private int resolveHealthCheckUnitId() {
        List<Integer> unitIds = collectUnitIds();
        return unitIds.isEmpty() ? 1 : unitIds.get(0);
    }

    /**
     * 清理或删除业务数据。
     */
    private void removeConnectionSilently() {
        removeManagedConnection("PLC4X Modbus TCP");
        connectionAdapter = null;
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
        return future.get(timeout, TimeUnit.MILLISECONDS);
    }

    /**
     * 校验业务条件和参数边界。
     */
    private Plc4xModbusTcpConnectionAdapter requireConnection() {
        if (connectionAdapter == null) {
            throw new IllegalStateException("PLC4X Modbus TCP connection has not been established");
        }
        return connectionAdapter;
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
