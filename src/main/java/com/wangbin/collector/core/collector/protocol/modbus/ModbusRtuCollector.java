package com.wangbin.collector.core.collector.protocol.modbus;

import com.digitalpetri.modbus.client.ModbusRtuClient;
import com.digitalpetri.modbus.pdu.*;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.enums.DataType;
import com.wangbin.collector.common.enums.Parity;
import com.wangbin.collector.core.collector.protocol.modbus.base.AbstractModbusCollector;
import com.wangbin.collector.core.collector.protocol.modbus.base.ModbusTransport;
import com.wangbin.collector.core.collector.protocol.modbus.domain.ModbusAddress;
import com.wangbin.collector.core.collector.protocol.modbus.domain.ModbusRequestBuilder;
import com.wangbin.collector.core.collector.protocol.modbus.domain.RegisterType;
import com.wangbin.collector.core.collector.protocol.modbus.utils.ModbusUtils;
import com.wangbin.collector.core.connection.adapter.ModbusRtuConnectionAdapter;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.*;

/**
 * Modbus RTU采集器（使用modbus-master-tcp库）
 */
@Slf4j
@Component
public class ModbusRtuCollector extends AbstractModbusCollector {
    private ModbusRtuConnectionAdapter connectionAdapter;
    private ModbusRtuClient client;
    private String serialPort;
    private int baudRate;
    private int dataBits;
    private int stopBits;
    private Parity parity = Parity.none;
    private int slaveId;
    private int timeout;
    private ByteOrder byteOrder = ByteOrder.BIG_ENDIAN;
    private int interFrameDelay = 5; // 帧间延时(ms)\r\n
    private final ModbusTransport transport = new ModbusTransport() {
        @Override
        public byte[] read(int unitId, RegisterType registerType, int startAddress, int quantity) throws Exception {
            return switch (registerType) {
                case COIL -> {
                    CompletionStage<ReadCoilsResponse> future = client.readCoilsAsync(
                            unitId,
                            new ReadCoilsRequest(startAddress, quantity));
                    ReadCoilsResponse response = rtuWait(future);
                    yield response.coils();
                }
                case DISCRETE_INPUT -> {
                    CompletionStage<ReadDiscreteInputsResponse> future = client.readDiscreteInputsAsync(
                            unitId,
                            new ReadDiscreteInputsRequest(startAddress, quantity));
                    ReadDiscreteInputsResponse response = rtuWait(future);
                    yield response.inputs();
                }
                case HOLDING_REGISTER -> {
                    CompletionStage<ReadHoldingRegistersResponse> future = client.readHoldingRegistersAsync(
                            unitId,
                            new ReadHoldingRegistersRequest(startAddress, quantity));
                    ReadHoldingRegistersResponse response = rtuWait(future);
                    yield response.registers();
                }
                case INPUT_REGISTER -> {
                    CompletionStage<ReadInputRegistersResponse> future = client.readInputRegistersAsync(
                            unitId,
                            new ReadInputRegistersRequest(startAddress, quantity));
                    ReadInputRegistersResponse response = rtuWait(future);
                    yield response.registers();
                }
            };
        }

        @Override
        public boolean writeMultipleCoils(int unitId, int startAddress, int quantity, byte[] coilBytes) throws Exception {
            WriteMultipleCoilsRequest request = new WriteMultipleCoilsRequest(startAddress, quantity, coilBytes);
            CompletionStage<WriteMultipleCoilsResponse> future = client.writeMultipleCoilsAsync(unitId, request);
            try {
                WriteMultipleCoilsResponse response = rtuWait(future);
                return response != null;
            } finally {
                ReferenceCountUtil.release(request);
            }
        }

        @Override
        public boolean writeMultipleRegisters(int unitId, int startAddress, short[] registers) throws Exception {
            WriteMultipleRegistersRequest request = ModbusRequestBuilder.buildWriteMultipleRegisters(
                    startAddress,
                    registers
            );
            CompletionStage<WriteMultipleRegistersResponse> future = client.writeMultipleRegistersAsync(unitId, request);
            try {
                WriteMultipleRegistersResponse response = rtuWait(future);
                return response != null;
            } finally {
                ReferenceCountUtil.release(request);
            }
        }
    };

    @Override
    public String getCollectorType() {
        return "ModbusRTU";
    }

    @Override
    public String getProtocolType() {
        return "MODBUS_RTU";
    }

    @Override
    protected void doConnect() throws Exception {
        log.info("开始建?Modbus RTU 连接: {}", deviceInfo.getDeviceId());
        DeviceConnection connectionConfig = requireConnectionConfig();

        interFrameDelay = connectionConfig.getInt("interFrameDelay", 5);
        serialPort = connectionConfig.getString("serialPort", "COM1");
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
                ModbusRtuConnectionAdapter.class,
                "Modbus RTU");
        this.client = connectionAdapter.getClient();

        log.info("Modbus RTU连接建立成功: port={} baud={} dataBits={} stopBits={} parity={}",
                serialPort, baudRate, dataBits, stopBits, parity.name());
    }

    @Override
    protected void doDisconnect() throws Exception {
        removeConnectionSilently();
        registerCache.clear();
        log.info("Modbus RTU连接已断开");
    }
    @Override
    protected Object doReadPoint(DataPoint point) throws Exception {
        String address = point.getAddress();
        if (address == null || address.isEmpty()) {
            throw new IllegalArgumentException("点位地址不能为空");
        }

        // 解析Modbus地址
        ModbusAddress modbusAddress = parseModbusAddress(address);
        int unitId = sanitizeUnitId(resolveUnitId(point));

        // 根据寄存器类型读取数?
        return switch (modbusAddress.getRegisterType()) {
            case COIL -> readCoil(unitId, modbusAddress);
            case DISCRETE_INPUT -> readDiscreteInput(unitId, modbusAddress);
            case HOLDING_REGISTER -> readHoldingRegister(unitId, modbusAddress, point.getDataType());
            case INPUT_REGISTER -> readInputRegister(unitId, modbusAddress, point.getDataType());
            default -> throw new IllegalArgumentException("不支持的Modbus寄存器类? " +
                    modbusAddress.getRegisterType());
        };
    }

    @Override
    protected boolean doWritePoint(DataPoint point, Object value) throws Exception {
        String address = point.getAddress();
        if (address == null || address.isEmpty()) {
            throw new IllegalArgumentException("点位地址不能为空");
        }

        ModbusAddress modbusAddress = parseModbusAddress(address);
        int unitId = sanitizeUnitId(resolveUnitId(point));

        return switch (modbusAddress.getRegisterType()) {
            case COIL -> writeCoil(unitId, modbusAddress, (Boolean) value);
            case HOLDING_REGISTER -> writeHoldingRegister(unitId, modbusAddress, value, point.getDataType());
            default -> throw new IllegalArgumentException("该寄存器类型不支持写? " +
                    modbusAddress.getRegisterType());
        };
    }

    @Override
    protected Map<String, Object> doGetDeviceStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("protocol", getProtocolType());
        status.put("serialPort", serialPort);
        status.put("baudRate", baudRate);
        status.put("dataBits", dataBits);
        status.put("stopBits", stopBits);
        status.put("parity", parity.name());
        status.put("slaveId", slaveId);
        status.put("timeout", timeout);
        status.put("byteOrder", byteOrder.toString());
        status.put("clientConnected", isConnected());
        status.put("interFrameDelay", interFrameDelay);

        // 统计订阅信息
        int totalSubscribed = 0;
        Map<String, Integer> subscribedByType = new HashMap<>();
        for (Map.Entry<RegisterType, Map<Integer, DataPoint>> entry : registerCache.entrySet()) {
            int count = entry.getValue().size();
            subscribedByType.put(entry.getKey().name(), count);
            totalSubscribed += count;
        }
        status.put("subscribedPoints", totalSubscribed);
        status.put("subscribedByType", subscribedByType);

        // 测试连接
        try {
            boolean connected = testConnection(slaveId);
            status.put("deviceConnected", connected);
        } catch (Exception e) {
            status.put("deviceConnected", false);
            status.put("connectionError", e.getMessage());
        }

        return status;
    }

    @Override
    protected Object doExecuteCommand(int unitId,String command, Map<String, Object> params) throws Exception {
        Map<String, Object> safeParams = params != null ? params : Collections.emptyMap();
        int targetUnitId = safeParams.containsKey("slaveId") ? sanitizeUnitId(unitId) : slaveId;

        return switch (command.toUpperCase()) {
            case "READ_MULTIPLE_REGISTERS" -> executeReadMultipleRegisters(targetUnitId, safeParams);
            case "WRITE_MULTIPLE_REGISTERS" -> executeWriteMultipleRegisters(targetUnitId, safeParams);
            case "READ_COILS" -> executeReadCoils(targetUnitId, safeParams);
            case "WRITE_COILS" -> executeWriteCoils(targetUnitId, safeParams);
            case "DIAGNOSTIC" -> executeDiagnostic(targetUnitId, safeParams);
            case "READ_EXCEPTION_STATUS" -> executeReadExceptionStatus(targetUnitId, safeParams);
            case "DIAGNOSTICS" -> executeDiagnostics(targetUnitId, safeParams);
            case "GET_COMM_EVENT_COUNTER" -> executeGetCommEventCounter(targetUnitId, safeParams);
            case "GET_COMM_EVENT_LOG" -> executeGetCommEventLog(targetUnitId, safeParams);
            default -> throw new IllegalArgumentException("不支持的Modbus命令: " + command);
        };
    }

    // =============== Modbus操作实现 ===============

    /**
     * 读取线圈
     */
    private Boolean readCoil(int unitId, ModbusAddress address) throws Exception {
        CompletionStage<ReadCoilsResponse> future = client.readCoilsAsync(unitId,
                new ReadCoilsRequest(address.getAddress(), 1));
        // 阻塞等待结果
        ReadCoilsResponse response = rtuWait(future);
        return ModbusUtils.parseCoilValue(response.coils(), 0, parity);
    }

    /**
     * 读取离散输入
     */
    private Boolean readDiscreteInput(int unitId, ModbusAddress address) throws Exception {
        CompletionStage<ReadDiscreteInputsResponse> future = client.readDiscreteInputsAsync(unitId,
                new ReadDiscreteInputsRequest(address.getAddress(), 1));
        // 阻塞等待结果
        ReadDiscreteInputsResponse response = rtuWait(future);
        return ModbusUtils.parseCoilValue(response.inputs(), 0, parity);
    }

    /**
     * 读取保持寄存?
     */
    private Object readHoldingRegister(int unitId, ModbusAddress address, String dataType) throws Exception {
        // 使用工具类获取寄存器数量
        int registerCount = DataType.fromString(dataType).getRegisterCount();

        CompletionStage<ReadHoldingRegistersResponse> future = client.readHoldingRegistersAsync(
                unitId,
                new ReadHoldingRegistersRequest(address.getAddress(), registerCount)
        );
        ReadHoldingRegistersResponse response = rtuWait(future);
        return ModbusUtils.convertByteToValue(response.registers(), dataType, byteOrder);
    }

    /**
     * 读取输入寄存?
     */
    private Object readInputRegister(int unitId, ModbusAddress address, String dataType) throws Exception {
        // 使用工具类获取寄存器数量
        int registerCount = DataType.fromString(dataType).getRegisterCount();

        CompletionStage<ReadInputRegistersResponse> future = client.readInputRegistersAsync(
                unitId,
                new ReadInputRegistersRequest(address.getAddress(), registerCount)
        );
        ReadInputRegistersResponse response = rtuWait(future);
        return ModbusUtils.convertByteToValue(response.registers(), dataType, byteOrder);
    }

    /**
     * 写入线圈
     */
    private boolean writeCoil(int unitId, ModbusAddress address, boolean value) throws Exception {
        CompletionStage<WriteSingleCoilResponse> future = client.writeSingleCoilAsync(
                unitId,
                new WriteSingleCoilRequest(address.getAddress(), value)
        );
        WriteSingleCoilResponse response = rtuWait(future);
        return response != null;
    }

    /**
     * 写入保持寄存?
     */
    private boolean writeHoldingRegister(int unitId, ModbusAddress address, Object value, String dataType) throws Exception {
        // 使用工具类获取寄存器数量和转�?
        int registerCount = DataType.fromString(dataType).getRegisterCount();
        short[] registers = ModbusUtils.valueToRegisters(value, dataType, byteOrder);

        if (registerCount == 1) {
            // 写入单个寄存?
            WriteSingleRegisterRequest request = new WriteSingleRegisterRequest(address.getAddress(), registers[0]);
            CompletionStage<WriteSingleRegisterResponse> future = client.writeSingleRegisterAsync(unitId, request);

            try {
                WriteSingleRegisterResponse response = rtuWait(future);
                return response != null;
            } finally {
                ReferenceCountUtil.release(request);
            }
        } else {
            // 写入多个寄存?
            // 使用工具类构建请求数?
            byte[] registerData = ModbusUtils.buildWriteRegistersData(registers);
            WriteMultipleRegistersRequest request = new WriteMultipleRegistersRequest(
                    address.getAddress(), registerCount, registerData);
            CompletionStage<WriteMultipleRegistersResponse> future = client.writeMultipleRegistersAsync(unitId, request);
            try {
                WriteMultipleRegistersResponse response = rtuWait(future);
                return response != null;
            } finally {
                ReferenceCountUtil.release(request);
            }
        }
    }

    // =============== 命令执行方法 ===============

    private Object executeReadMultipleRegisters(int unitId, Map<String, Object> params) throws Exception {
        int address = (int) params.getOrDefault("address", 0);
        int quantity = (int) params.getOrDefault("quantity", 1);

        ReadHoldingRegistersRequest request = new ReadHoldingRegistersRequest(address, quantity);
        CompletionStage<ReadHoldingRegistersResponse> future = client.readHoldingRegistersAsync(unitId, request);

        try {
            ReadHoldingRegistersResponse response = rtuWait(future);
            List<Short> values = new ArrayList<>();

            if (response != null && response.registers() != null) {
                byte[] raw = response.registers();
                ByteBuffer buffer = ByteBuffer.wrap(raw);
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

    private Object executeWriteMultipleRegisters(int unitId, Map<String, Object> params) throws Exception {
        int address = (int) params.getOrDefault("address", 0);
        @SuppressWarnings("unchecked")
        List<Integer> values = (List<Integer>) params.get("values");
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("values参数不能为空");
        }

        // 构建寄存器数?
        short[] registers = new short[values.size()];
        for (int i = 0; i < values.size(); i++) {
            registers[i] = values.get(i).shortValue();
        }

        // 使用工具类构建请求数?
        byte[] registerData = ModbusUtils.buildWriteRegistersData(registers);
        WriteMultipleRegistersRequest request = new WriteMultipleRegistersRequest(
                address, registers.length, registerData);
        CompletionStage<WriteMultipleRegistersResponse> future = client.writeMultipleRegistersAsync(unitId, request);

        try {
            WriteMultipleRegistersResponse response = rtuWait(future);
            return Map.of(
                    "success", response != null,
                    "address", address,
                    "quantity", values.size()
            );
        } finally {
            ReferenceCountUtil.release(request);
        }
    }

    private Object executeReadCoils(int unitId, Map<String, Object> params) throws Exception {
        int address = (int) params.getOrDefault("address", 0);
        int quantity = (int) params.getOrDefault("quantity", 1);

        ReadCoilsRequest request = new ReadCoilsRequest(address, quantity);
        CompletionStage<ReadCoilsResponse> future = client.readCoilsAsync(unitId, request);

        try {
            ReadCoilsResponse response = rtuWait(future);
            // 使用工具类解析线�?
        List<Boolean> values = ModbusUtils.getCoilValues(response.coils(), quantity, parity);

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

    private Object executeWriteCoils(int unitId, Map<String, Object> params) throws Exception {
        int address = (int) params.getOrDefault("address", 0);
        @SuppressWarnings("unchecked")
        List<Boolean> values = (List<Boolean>) params.get("values");
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("values参数不能为空");
        }

        int quantity = values.size();
        // 使用工具类构建线圈字节数?
        byte[] coilBytes = ModbusUtils.buildCoilBytes(values, parity);

        WriteMultipleCoilsRequest request = new WriteMultipleCoilsRequest(address, quantity, coilBytes);
        CompletionStage<WriteMultipleCoilsResponse> future = client.writeMultipleCoilsAsync(unitId, request);

        try {
            WriteMultipleCoilsResponse response = rtuWait(future);
            return Map.of(
                    "success", response != null,
                    "address", address,
                    "quantity", quantity
            );
        } finally {
            ReferenceCountUtil.release(request);
        }
    }

    private Object executeReadExceptionStatus(int unitId, Map<String, Object> params) throws Exception {
        try {
            // 使用工具类构建RTU请求?
            byte[] requestData = ModbusUtils.buildRtuExceptionStatusRequest(unitId);

            // 这里你后面直接走串口?requestData 即可
            // serialPort.writeBytes(requestData, requestData.length);

            return Map.of(
                    "success", true,
                    "request", requestData
            );
        } catch (Exception e) {
            throw new Exception("读取异常状态失? " + e.getMessage(), e);
        }
    }

    private Object executeDiagnostics(int unitId, Map<String, Object> params) throws Exception {
        try {
            int subFunction = (int) params.getOrDefault("subFunction", 0x0000);
            int data = (int) params.getOrDefault("data", 0x0000);

            // 使用工具类构建RTU诊断请求
            byte[] requestData = ModbusUtils.buildRtuDiagnosticRequest(unitId, subFunction, data);

            // serialPort.writeBytes(requestData, requestData.length);

            return Map.of(
                    "success", true,
                    "request", requestData
            );
        } catch (Exception e) {
            throw new Exception("诊断功能执行失败: " + e.getMessage(), e);
        }
    }

    private Object executeGetCommEventCounter(int unitId, Map<String, Object> params) throws Exception {
        return Map.of(
                "success", false,
                "message", "获取通信事件计数器功能需要底层实现"
        );
    }

    private Object executeGetCommEventLog(int unitId, Map<String, Object> params) throws Exception {
        return Map.of(
                "success", false,
                "message", "获取通信事件日志功能需要底层实现"
        );
    }

    private Object executeDiagnostic(int unitId, Map<String, Object> params) {
        Map<String, Object> result = new HashMap<>();
        result.put("protocol", getProtocolType());
        result.put("serialPort", serialPort);
        result.put("baudRate", baudRate);
        result.put("dataBits", dataBits);
        result.put("stopBits", stopBits);
        result.put("parity", parity.name());
        result.put("slaveId", unitId);
        result.put("timeout", timeout);
        result.put("byteOrder", byteOrder.toString());
        result.put("clientConnected", isConnected());
        result.put("interFrameDelay", interFrameDelay);
        result.put("timestamp", System.currentTimeMillis());

        // 测试连接
        try {
            boolean connected = testConnection(unitId);
            result.put("deviceConnected", connected);
            result.put("connectionTest", "SUCCESS");
        } catch (Exception e) {
            result.put("deviceConnected", false);
            result.put("connectionTest", "FAILED");
            result.put("error", e.getMessage());
        }

        return result;
    }

    // =============== 辅助方法 ===============

    private int sanitizeUnitId(Integer unitIdValue) {
        return unitIdValue != null && unitIdValue > 0 ? unitIdValue : slaveId;
    }

    /**
     * 测试连接
     */
    private boolean testConnection() throws Exception {
        return testConnection(slaveId);
    }

    private boolean testConnection(int unitId) throws Exception {
        try {
            CompletionStage<ReadHoldingRegistersResponse> future = client.readHoldingRegistersAsync(
                    unitId, new ReadHoldingRegistersRequest(0, 1));
            rtuWait(future);
            return true;
        } catch (Exception e) {
            log.warn("连接测试失败", e);
            return false;
        }
    }

    /**
     * RTU等待方法（带帧间隔）
     */
    private <T> T rtuWait(CompletionStage<T> future) throws Exception {
        T result = future.toCompletableFuture()
                .get(timeout, TimeUnit.MILLISECONDS);

        // RTU 帧间隔，3.5 字符时间
        if (interFrameDelay > 0) {
            Thread.sleep(interFrameDelay);
        }
        return result;
    }


    private void removeConnectionSilently() {
        removeManagedConnection("Modbus RTU");
        connectionAdapter = null;
        client = null;
    }

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

