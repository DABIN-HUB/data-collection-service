package com.wangbin.collector.core.collector.protocol.modbus;

import com.digitalpetri.modbus.pdu.*;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.enums.DataType;
import com.wangbin.collector.common.enums.Parity;
import com.wangbin.collector.core.collector.protocol.modbus.base.AbstractModbusCollector;
import com.wangbin.collector.core.collector.protocol.modbus.base.ModbusTransport;
import com.wangbin.collector.core.collector.protocol.modbus.domain.ModbusAddress;
import com.wangbin.collector.core.collector.protocol.modbus.domain.ModbusRequestBuilder;
import com.wangbin.collector.core.collector.protocol.modbus.domain.RegisterType;
import com.wangbin.collector.core.collector.protocol.modbus.plan.ModbusReadPlan;
import com.wangbin.collector.core.collector.protocol.modbus.utils.ModbusUtils;
import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.connection.adapter.ModbusTcpConnectionAdapter;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * Modbus TCP采集器
 */
@Slf4j
@Component
public class ModbusTcpCollector extends AbstractModbusCollector {

    private ModbusTcpConnectionAdapter connectionAdapter;
    private ByteOrder byteOrder = ByteOrder.BIG_ENDIAN;
    private Parity parity = Parity.none;
    private final ModbusTransport transport = new ModbusTransport() {
        @Override
        public byte[] read(int unitId, RegisterType registerType,
                           int startAddress, int quantity) throws Exception {
            return switch (registerType) {
                case COIL -> executeWithClient(client -> {
                    ReadCoilsResponse response = await(client.readCoilsAsync(
                            unitId,
                            new ReadCoilsRequest(startAddress, quantity)
                    ));
                    return response.coils();
                });
                case DISCRETE_INPUT -> executeWithClient(client -> {
                    ReadDiscreteInputsResponse response = await(client.readDiscreteInputsAsync(
                            unitId,
                            new ReadDiscreteInputsRequest(startAddress, quantity)
                    ));
                    return response.inputs();
                });
                case HOLDING_REGISTER -> executeWithClient(client -> {
                    ReadHoldingRegistersResponse response = await(client.readHoldingRegistersAsync(
                            unitId,
                            new ReadHoldingRegistersRequest(startAddress, quantity)
                    ));
                    return response.registers();
                });
                case INPUT_REGISTER -> executeWithClient(client -> {
                    ReadInputRegistersResponse response = await(client.readInputRegistersAsync(
                            unitId,
                            new ReadInputRegistersRequest(startAddress, quantity)
                    ));
                    return response.registers();
                });
            };
        }

        @Override
        public boolean writeMultipleCoils(int unitId, int startAddress, int quantity, byte[] coilBytes) throws Exception {
            WriteMultipleCoilsRequest request = new WriteMultipleCoilsRequest(startAddress, quantity, coilBytes);
            try {
                return executeWithClient(client -> {
                    WriteMultipleCoilsResponse response = client.writeMultipleCoils(unitId, request);
                    return response != null;
                });
            } finally {
                ReferenceCountUtil.release(request);
            }
        }

        @Override
        public boolean writeMultipleRegisters(int unitId, int startAddress, short[] registers) throws Exception {
            WriteMultipleRegistersRequest request = ModbusRequestBuilder.buildWriteMultipleRegisters(startAddress, registers);
            try {
                return executeWithClient(client -> {
                    CompletionStage<WriteMultipleRegistersResponse> future = client.writeMultipleRegistersAsync(unitId, request);
                    WriteMultipleRegistersResponse response = await(future);
                    return response != null;
                });
            } finally {
                ReferenceCountUtil.release(request);
            }
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

    @Override
    protected void doConnect() throws Exception {
        log.info("开始建立Modbus TCP连接: {}", deviceInfo.getDeviceId());
        DeviceConnection desiredConfig = requireConnectionConfig();
        this.connectionAdapter = createAndConnectAdapter(
                desiredConfig,
                ModbusTcpConnectionAdapter.class,
                "Modbus TCP");

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
        byteOrder = ModbusUtils.parseByteOrder(connectionConfig.getString("byteOrder","BIG_ENDIAN"));
        String parityName = connectionConfig.getString("parity", Parity.none.name());
        parity = Parity.fromName(parityName != null ? parityName.toLowerCase() : Parity.none.name());
        log.info("Modbus TCP连接建立成功: {}:{} byteOrder={} parity={}",
                connectionConfig.getHost(),
                connectionConfig.getPort(),
                byteOrder,
                parity.name());
    }

    @Override
    protected void doDisconnect() throws Exception {
        removeConnectionSilently();
        registerCache.clear();
        log.info("Modbus TCP连接已断开");
    }


    @Override
    protected Object doReadPoint(DataPoint point) throws Exception {
        String address = point.getAddress();
        if (address == null || address.isEmpty()) {
            throw new IllegalArgumentException("点位地址不能为空");
        }

        ModbusAddress modbusAddress = parseModbusAddress(address);
        int unitId = resolveUnitId(point);
        return switch (modbusAddress.getRegisterType()) {
            case COIL -> readCoil(unitId,modbusAddress);
            case DISCRETE_INPUT -> readDiscreteInput(unitId,modbusAddress);
            case HOLDING_REGISTER -> readHoldingRegister(unitId,modbusAddress, point.getDataType());
            case INPUT_REGISTER -> readInputRegister(unitId,modbusAddress, point.getDataType());
            default -> throw new IllegalArgumentException("不支持的寄存器类型 " + modbusAddress.getRegisterType());
        };
    }

    @Override
    protected boolean doWritePoint(DataPoint point, Object value) throws Exception {
        String address = point.getAddress();
        if (address == null || address.isEmpty()) {
            throw new IllegalArgumentException("点位地址不能为空");
        }

        ModbusAddress modbusAddress = parseModbusAddress(address);
        int unitId = resolveUnitId(point);
        return switch (modbusAddress.getRegisterType()) {
            case COIL -> writeCoil(unitId,modbusAddress, (Boolean) value);
            case HOLDING_REGISTER -> writeHoldingRegister(unitId,modbusAddress, value, point.getDataType());
            default -> throw new IllegalArgumentException("该寄存器类型不支持写�? " + modbusAddress.getRegisterType());
        };
    }

    @Override
    protected Map<String, Object> doGetDeviceStatus() {
        Map<String, Object> status = getBaseDeviceStatus("Modbus TCP");
        status.put("unitIds", collectUnitIds());
        status.put("byteOrder", byteOrder.toString());
        status.put("parity", parity.name());
        // 测试连接
        try {
            boolean connected = testConnection();
            status.put("deviceConnected", connected);
        } catch (Exception e) {
            status.put("deviceConnected", false);
            status.put("connectionError", e.getMessage());
        }

        return status;
    }

    @Override
    protected Object doExecuteCommand(int unitId,String command, Map<String, Object> params) throws Exception {
        return switch (command.toUpperCase()) {
            case "READ_MULTIPLE_REGISTERS" -> executeReadMultipleRegisters(unitId,params);
            case "WRITE_MULTIPLE_REGISTERS" -> executeWriteMultipleRegisters(unitId,params);
            case "READ_COILS" -> executeReadCoils(unitId,params);
            case "WRITE_COILS" -> executeWriteCoils(unitId,params);
            case "DIAGNOSTIC" -> executeDiagnostic(unitId,params);
            default -> throw new IllegalArgumentException("不支持的Modbus命令: " + command);
        };
    }

    // =============== Modbus操作实现 ===============

    private Boolean readCoil(int unitId,ModbusAddress address) throws Exception {
        return executeWithClient(client -> {
            CompletionStage<ReadCoilsResponse> future = client.readCoilsAsync(unitId,
                    new ReadCoilsRequest(address.getAddress(), 1));
            ReadCoilsResponse response = await(future);
            return ModbusUtils.parseCoilValue(response.coils(), 0, parity);
        });
    }


    private Boolean readDiscreteInput(int unitId,ModbusAddress address) throws Exception {
        return executeWithClient(client -> {
            CompletionStage<ReadDiscreteInputsResponse> future = client.readDiscreteInputsAsync(unitId,
                    new ReadDiscreteInputsRequest(address.getAddress(), 1));
            ReadDiscreteInputsResponse response = await(future);
            return ModbusUtils.parseCoilValue(response.inputs(), 0, parity);
        });
    }


    private Object readHoldingRegister(int unitId,ModbusAddress address, String dataType) throws Exception {
        int registerCount = DataType.fromString(dataType).getRegisterCount();
        return executeWithClient(client -> {
            CompletionStage<ReadHoldingRegistersResponse> future = client.readHoldingRegistersAsync(
                    unitId,
                    new ReadHoldingRegistersRequest(address.getAddress(), registerCount)
            );
            ReadHoldingRegistersResponse response = await(future);
            return ModbusUtils.parseRegisterValue(response.registers(), dataType, byteOrder);
        });
    }


    private Object readInputRegister(int unitId,ModbusAddress address, String dataType) throws Exception {
        int registerCount = DataType.fromString(dataType).getRegisterCount();
        return executeWithClient(client -> {
            CompletionStage<ReadInputRegistersResponse> future = client.readInputRegistersAsync(
                    unitId,
                    new ReadInputRegistersRequest(address.getAddress(), registerCount)
            );
            ReadInputRegistersResponse response = await(future);
            return ModbusUtils.parseRegisterValue(response.registers(), dataType, byteOrder);
        });
    }


    private boolean writeCoil(int unitId,ModbusAddress address, boolean value) throws Exception {
        return executeWithClient(client -> {
            CompletionStage<WriteSingleCoilResponse> future = client.writeSingleCoilAsync(
                    unitId,
                    new WriteSingleCoilRequest(address.getAddress(), value)
            );
            WriteSingleCoilResponse response = await(future);
            return response != null;
        });
    }


    private boolean writeHoldingRegister(int unitId,ModbusAddress address, Object value, String dataType) throws Exception {
        int registerCount = DataType.fromString(dataType).getRegisterCount();
        short[] registers = ModbusUtils.valueToRegisters(value, dataType, byteOrder);

        if (registerCount == 1) {
            WriteSingleRegisterRequest request = new WriteSingleRegisterRequest(address.getAddress(), registers[0]);
            try {
                return executeWithClient(client -> {
                    CompletionStage<WriteSingleRegisterResponse> future = client.writeSingleRegisterAsync(unitId, request);
                    WriteSingleRegisterResponse response = await(future);
                    return response != null;
                });
            } finally {
                ReferenceCountUtil.release(request);
            }
        } else {
            WriteMultipleRegistersRequest request = ModbusRequestBuilder.buildWriteMultipleRegisters(address.getAddress(), registers);
            try {
                return executeWithClient(client -> {
                    CompletionStage<WriteMultipleRegistersResponse> future = client.writeMultipleRegistersAsync(unitId, request);
                    WriteMultipleRegistersResponse response = await(future);
                    return response != null;
                });
            } finally {
                ReferenceCountUtil.release(request);
            }
        }
    }


    // =============== 命令执行方法 ===============

    private Object executeReadMultipleRegisters(int unitId,Map<String, Object> params) throws Exception {
        int address = (int) params.getOrDefault("address", 0);
        int quantity = (int) params.getOrDefault("quantity", 1);
        ReadHoldingRegistersRequest request = new ReadHoldingRegistersRequest(address, quantity);

        try {
            ReadHoldingRegistersResponse response = executeWithClient(client -> {
                CompletionStage<ReadHoldingRegistersResponse> future = client.readHoldingRegistersAsync(unitId, request);
                return await(future);
            });
            List<Short> values = new ArrayList<>();

            if (response != null && response.registers() != null) {
                byte[] raw = response.registers();
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(raw);
                buffer.order(byteOrder);

                for (int i = 0; i < quantity; i++) {
                    values.add(buffer.getShort());
                }
            }

            return Map.of(
                    "success", response != null,
                    "address", address,
                    "quantity", quantity,
                    "values", values
            );
        } finally {
            ReferenceCountUtil.release(request);
        }
    }


    private Object executeWriteMultipleRegisters(int unitId,Map<String, Object> params) throws Exception {
        int address = (int) params.getOrDefault("address", 0);
        @SuppressWarnings("unchecked")
        List<Integer> values = (List<Integer>) params.get("values");
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("values参数不能为空");
        }

        WriteMultipleRegistersRequest request = ModbusRequestBuilder.buildWriteMultipleRegisters(address, values);

        try {
            WriteMultipleRegistersResponse response = executeWithClient(client -> {
                CompletionStage<WriteMultipleRegistersResponse> future = client.writeMultipleRegistersAsync(unitId, request);
                return await(future);
            });
            return Map.of(
                    "success", response != null,
                    "address", address,
                    "quantity", values.size()
            );
        } finally {
            ReferenceCountUtil.release(request);
        }
    }


    private Object executeReadCoils(int unitId,Map<String, Object> params) throws Exception {
        int address = (int) params.getOrDefault("address", 0);
        int quantity = (int) params.getOrDefault("quantity", 1);

        ReadCoilsResponse response = executeWithClient(client -> {
            CompletionStage<ReadCoilsResponse> future = client.readCoilsAsync(unitId, new ReadCoilsRequest(address, quantity));
            return await(future);
        });
        List<Boolean> values = ModbusUtils.getCoilValues(response.coils(), quantity, parity);

        return Map.of(
                "success", true,
                "address", address,
                "quantity", quantity,
                "values", values
        );
    }


    private Object executeWriteCoils(int unitId,Map<String, Object> params) throws Exception {
        int address = (int) params.getOrDefault("address", 0);
        @SuppressWarnings("unchecked")
        List<Boolean> values = (List<Boolean>) params.get("values");
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("values参数不能为空");
        }

        byte[] coilBytes = ModbusUtils.buildCoilBytes(values, parity);
        WriteMultipleCoilsResponse response = executeWithClient(client -> client.writeMultipleCoils(
                unitId,
                new WriteMultipleCoilsRequest(address, values.size(), coilBytes)
        ));

        return Map.of(
                "success", response != null,
                "address", address,
                "quantity", values.size()
        );
    }

    private Object executeDiagnostic(int unitId,Map<String, Object> params) {
        Map<String, Object> result = new HashMap<>();
        result.put("protocol", "Modbus TCP");
        /*result.put("host", host);
        result.put("port", port);*/
        result.put("unitId", unitId);
        result.put("timeout", timeout);
        result.put("masterConnected", connectionAdapter != null && connectionAdapter.isConnected());
        result.put("timestamp", System.currentTimeMillis());

        try {
            boolean connected = testConnection();
            result.put("deviceConnected", connected);
            result.put("connectionTest", "SUCCESS");
        } catch (Exception e) {
            result.put("deviceConnected", false);
            result.put("connectionTest", "FAILED");
            result.put("error", e.getMessage());
        }

        return result;
    }

    // =============== 杈呭姪鏂规硶 ===============

    private boolean testConnection() {
        int unitId = resolveHealthCheckUnitId();
        try {
            executeWithClient(client -> {
                client.readHoldingRegisters(unitId, new ReadHoldingRegistersRequest(0, 1));
                return true;
            });
            return true;
        } catch (Exception e) {
            log.warn("连接测试失败", e);
            return false;
        }
    }


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
        return connection != null ? (Integer) connection.getProperty("slaveId") : null;
    }

    private <T> T await(CompletionStage<T> future) throws Exception {
        return future.toCompletableFuture().get(timeout, TimeUnit.MILLISECONDS);
    }

    private void removeConnectionSilently() {
        removeManagedConnection("Modbus TCP");
        connectionAdapter = null;
    }

    private int resolveHealthCheckUnitId() {
        List<Integer> unitIds = collectUnitIds();
        return unitIds.isEmpty() ? 1 : unitIds.get(0);
    }

    private <T> T executeWithClient(ModbusTcpConnectionAdapter.ModbusCallable<T> callable) throws Exception {
        return requireConnection().execute(callable, timeout);
    }

    private ModbusTcpConnectionAdapter requireConnection() {
        if (connectionAdapter == null) {
            throw new IllegalStateException("Modbus TCP连接尚未建立");
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







