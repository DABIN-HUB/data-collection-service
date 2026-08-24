package com.wangbin.collector.core.collector.protocol.modbus;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.connection.adapter.Plc4xModbusTcpConnectionAdapter;
import com.wangbin.collector.core.processor.DataQualityProcessor;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModbusTcpCollectorRealServerIT {

    @Test
    void shouldReadChangingHoldingRegistersFromConfiguredRealServer() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("modbus.real.enabled"),
                "未启用真实 Modbus TCP 从站验收");

        String host = System.getProperty("modbus.real.host", "127.0.0.1");
        int port = Integer.getInteger("modbus.real.port", 502);
        int unitId = Integer.getInteger("modbus.real.unit-id", 1);
        int startRegister = Integer.getInteger("modbus.real.start-register", 4001);
        int pointCount = Integer.getInteger("modbus.real.point-count", 10);
        long sampleIntervalMs = Long.getLong("modbus.real.sample-interval-ms", 1500L);

        DeviceInfo deviceInfo = deviceInfo(host, port);
        List<DataPoint> points = points(startRegister, pointCount, unitId);
        Plc4xModbusTcpConnectionAdapter adapter = new Plc4xModbusTcpConnectionAdapter(
                deviceInfo, connection(host, port, unitId));
        adapter.connect();
        try {
            Plc4xModbusTcpCollector collector = connectedCollector(deviceInfo, adapter, points);
            Map<String, Object> firstValues = collector.readPoints(points);
            Thread.sleep(sampleIntervalMs);
            Map<String, Object> secondValues = collector.readPoints(points);

            assertEquals(pointCount, firstValues.size());
            assertEquals(pointCount, secondValues.size());
            assertTrue(points.stream().anyMatch(point -> valuesChanged(
                    firstValues.get(point.getPointId()), secondValues.get(point.getPointId()))),
                    "配置的寄存器在采样窗口内没有检测到变化");
        } finally {
            adapter.disconnect();
        }
    }

    private DeviceInfo deviceInfo(String host, int port) {
        DeviceInfo deviceInfo = new DeviceInfo();
        deviceInfo.setDeviceId("real-modbus-acceptance-device");
        deviceInfo.setDeviceName("真实 Modbus TCP 验收设备");
        deviceInfo.setProtocolType("MODBUS_TCP");
        deviceInfo.setConnectionType("MODBUS_TCP");
        deviceInfo.setIpAddress(host);
        deviceInfo.setPort(port);
        deviceInfo.setCollectionInterval(1000);
        return deviceInfo;
    }

    private DeviceConnection connection(String host, int port, int unitId) {
        DeviceConnection connection = new DeviceConnection();
        connection.setConnectionType("MODBUS_TCP");
        connection.setHost(host);
        connection.setPort(port);
        connection.setReadTimeout(5000);
        connection.setTimeout(5000);
        connection.setExtJson(Map.of(
                "slaveId", unitId,
                "byteOrder", System.getProperty("modbus.real.byte-order", "BIG_ENDIAN"),
                "maxRegistersPerRequest", 125
        ));
        return connection;
    }

    private List<DataPoint> points(int startRegister, int pointCount, int unitId) {
        List<DataPoint> points = new ArrayList<>();
        for (int index = 0; index < pointCount; index++) {
            int register = startRegister + index;
            DataPoint point = new DataPoint();
            point.setPointId("real-point-" + register);
            point.setPointCode("real_register_" + register);
            point.setPointName("真实保持寄存器 " + register);
            point.setDeviceId("real-modbus-acceptance-device");
            point.setAddress(toHoldingRegisterReference(register));
            point.setDataType(System.getProperty("modbus.real.data-type", "UINT16"));
            point.setReadWrite("R");
            point.setUnitId(unitId);
            point.setStatus(1);
            point.setAdditionalConfig(Map.of());
            points.add(point);
        }
        return points;
    }

    private String toHoldingRegisterReference(int oneBasedRegister) {
        if (oneBasedRegister <= 0 || oneBasedRegister > 9999) {
            throw new IllegalArgumentException("真实验收寄存器地址必须在 1 到 9999 之间");
        }
        return "4" + String.format("%04d", oneBasedRegister);
    }

    private Plc4xModbusTcpCollector connectedCollector(DeviceInfo deviceInfo,
                                                        Plc4xModbusTcpConnectionAdapter adapter,
                                                        List<DataPoint> points) throws Exception {
        Plc4xModbusTcpCollector collector = new Plc4xModbusTcpCollector();
        collector.init(deviceInfo);
        ReflectionTestUtils.setField(collector, "dataQualityProcessor", com.wangbin.collector.core.processor.DataQualityProcessorTestSupport.create());
        ReflectionTestUtils.setField(collector, "connected", true);
        ReflectionTestUtils.setField(collector, "connectionStatus", "CONNECTED");
        ReflectionTestUtils.setField(collector, "connectionAdapter", adapter);
        collector.rebuildReadPlans(deviceInfo.getDeviceId(), points);
        return collector;
    }

    private boolean valuesChanged(Object firstValue, Object secondValue) {
        if (firstValue == null || secondValue == null) {
            return false;
        }
        if (firstValue instanceof Number firstNumber && secondValue instanceof Number secondNumber) {
            return Double.compare(firstNumber.doubleValue(), secondNumber.doubleValue()) != 0;
        }
        return !firstValue.equals(secondValue);
    }
}
