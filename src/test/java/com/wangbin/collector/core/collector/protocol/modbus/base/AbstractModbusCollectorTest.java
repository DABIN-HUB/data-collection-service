package com.wangbin.collector.core.collector.protocol.modbus.base;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.enums.Parity;
import com.wangbin.collector.core.collector.protocol.modbus.domain.RegisterType;
import com.wangbin.collector.core.collector.protocol.modbus.utils.ModbusUtils;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class AbstractModbusCollectorTest {

    @Test
    void shouldUseSharedReadPlanExecutor() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        transport.readResponse = ModbusUtils.buildCoilBytes(List.of(true, false), Parity.none);
        TestModbusCollector collector = new TestModbusCollector(transport);
        List<DataPoint> points = List.of(point("p1", "0:0", "BOOLEAN"), point("p2", "0:1", "BOOLEAN"));

        collector.rebuildReadPlans("dev-1", points);
        Map<String, Object> values = collector.readRaw(points);

        assertEquals(Boolean.TRUE, values.get("p1"));
        assertEquals(Boolean.FALSE, values.get("p2"));
        assertEquals(List.of(new ReadCall(1, RegisterType.COIL, 0, 2)), transport.readCalls);
    }

    @Test
    void shouldNotWriteNullWhenReadPlanFails() {
        RecordingTransport transport = new RecordingTransport();
        transport.readFailure = new IllegalStateException("read failed");
        TestModbusCollector collector = new TestModbusCollector(transport);
        List<DataPoint> points = List.of(point("p1", "0:0", "BOOLEAN"), point("p2", "0:1", "BOOLEAN"));

        collector.rebuildReadPlans("dev-1", points);
        Map<String, Object> values = collector.readRaw(points);

        assertEquals(true, values.isEmpty());
        assertEquals(List.of(new ReadCall(1, RegisterType.COIL, 0, 2)), transport.readCalls);
    }

    @Test
    void abstractModbusCollectorShouldUseInjectedExecutorWhenPresent() {
        RecordingTransport transport = new RecordingTransport();
        TestModbusCollector collector = new TestModbusCollector(transport);
        AtomicReference<Runnable> executed = new AtomicReference<>();
        Executor injected = executed::set;
        ReflectionTestUtils.setField(collector, "modbusReadExecutor", injected);

        assertSame(injected, collector.resolveModbusReadExecutor());
    }

    @Test
    void shouldUseSharedContiguousRegisterWriteBatch() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        TestModbusCollector collector = new TestModbusCollector(transport);
        Map<DataPoint, Object> points = Map.of(
                point("p1", "4:0", "INT16"), 11,
                point("p2", "4:1", "INT16"), 12
        );

        Map<String, Boolean> results = collector.writeRaw(points);

        assertEquals(Boolean.TRUE, results.get("p1"));
        assertEquals(Boolean.TRUE, results.get("p2"));
        assertEquals(1, transport.registerWrites.size());
        RegisterWrite write = transport.registerWrites.get(0);
        assertEquals(1, write.unitId());
        assertEquals(0, write.startAddress());
        assertArrayEquals(new short[]{11, 12}, write.registers());
        assertEquals(List.of(), collector.singleWrites);
    }

    private DataPoint point(String pointId, String address, String dataType) {
        DataPoint point = new DataPoint();
        point.setPointId(pointId);
        point.setPointName(pointId);
        point.setDeviceId("dev-1");
        point.setAddress(address);
        point.setDataType(dataType);
        point.setUnitId(1);
        return point;
    }

    private record ReadCall(int unitId, RegisterType registerType, int startAddress, int quantity) {
    }

    private record RegisterWrite(int unitId, int startAddress, short[] registers) {
        private RegisterWrite {
            registers = Arrays.copyOf(registers, registers.length);
        }
    }

    private static final class RecordingTransport implements ModbusTransport {
        private byte[] readResponse = new byte[0];
        private RuntimeException readFailure;
        private final List<ReadCall> readCalls = new ArrayList<>();
        private final List<RegisterWrite> registerWrites = new ArrayList<>();

        @Override
        public byte[] read(int unitId, RegisterType registerType, int startAddress, int quantity) {
            readCalls.add(new ReadCall(unitId, registerType, startAddress, quantity));
            if (readFailure != null) {
                throw readFailure;
            }
            return readResponse;
        }

        @Override
        public boolean writeMultipleCoils(int unitId, int startAddress, int quantity, byte[] coilBytes) {
            return true;
        }

        @Override
        public boolean writeMultipleRegisters(int unitId, int startAddress, short[] registers) {
            registerWrites.add(new RegisterWrite(unitId, startAddress, registers));
            return true;
        }
    }

    private static final class TestModbusCollector extends AbstractModbusCollector {
        private final ModbusTransport transport;
        private final List<String> singleWrites = new ArrayList<>();

        private TestModbusCollector(ModbusTransport transport) {
            this.transport = transport;
        }

        private Map<String, Object> readRaw(List<DataPoint> points) {
            return doReadPoints(points);
        }

        private Map<String, Boolean> writeRaw(Map<DataPoint, Object> points) throws Exception {
            return doWritePoints(points);
        }

        @Override
        protected void doConnect() {
        }

        @Override
        protected void doDisconnect() {
        }

        @Override
        protected Object doReadPoint(DataPoint point) {
            return null;
        }

        @Override
        protected boolean doWritePoint(DataPoint point, Object value) {
            singleWrites.add(point.getPointId());
            return true;
        }

        @Override
        protected Map<String, Object> doGetDeviceStatus() {
            return Map.of();
        }

        @Override
        protected Object doExecuteCommand(int unitId, String command, Map<String, Object> params) {
            return null;
        }

        @Override
        protected ModbusTransport getModbusTransport() {
            return transport;
        }

        @Override
        protected ByteOrder getModbusByteOrder() {
            return ByteOrder.BIG_ENDIAN;
        }

        @Override
        protected Parity getModbusParity() {
            return Parity.none;
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public String getCollectorType() {
            return "TEST_MODBUS";
        }

        @Override
        public String getProtocolType() {
            return "MODBUS_TEST";
        }
    }
}
