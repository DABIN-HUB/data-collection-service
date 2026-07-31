package com.wangbin.collector.core.collector.protocol.modbus;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.alarm.AlarmMetadataKeys;
import com.wangbin.collector.core.cache.aspect.CollectorDataPostProcessor;
import com.wangbin.collector.core.cache.aspect.TelemetryPostProcessContext;
import com.wangbin.collector.core.cache.aspect.TelemetryPostProcessPipeline;
import com.wangbin.collector.core.cache.aspect.TelemetryPostProcessStage;
import com.wangbin.collector.core.cache.aspect.TelemetryStageType;
import com.wangbin.collector.core.cloud.model.CloudDeviceType;
import com.wangbin.collector.core.cloud.model.CloudTargetConfig;
import com.wangbin.collector.core.collector.protocol.modbus.support.FakeModbusTcpServer;
import com.wangbin.collector.core.collector.scheduler.CollectionTaskGuard;
import com.wangbin.collector.core.connection.adapter.Plc4xModbusTcpConnectionAdapter;
import com.wangbin.collector.core.processor.DataQualityProcessor;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.core.processor.ProcessResultMetadataKeys;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ModbusTcpCollectionChainAcceptanceTest {

    private static final String LOCAL_DEVICE_ID = "local-modbus-device-001";
    private static final int START_REGISTER = 4001;
    private static final int POINT_COUNT = 10;

    @Test
    void shouldCollectChangingRegistersAndPreserveLocalDeviceIdentityAcrossPostProcessing() throws Exception {
        try (FakeModbusTcpServer server = new FakeModbusTcpServer()) {
            initializeRegisters(server);
            DeviceInfo deviceInfo = deviceInfo();
            List<DataPoint> points = points();
            Plc4xModbusTcpConnectionAdapter adapter = new Plc4xModbusTcpConnectionAdapter(
                    deviceInfo, connection(server.port()));
            adapter.connect();

            try {
                Plc4xModbusTcpCollector collector = connectedCollector(deviceInfo, adapter, points);
                Map<String, Object> firstValues = collector.readPoints(points);
                Map<String, ProcessResult> processResults = collector.takeInvocationProcessResults();

                assertEquals(POINT_COUNT, firstValues.size());
                assertEquals(21.0d, ((Number) firstValues.get("point-4001")).doubleValue(), 0.0001d);
                assertEquals(109, ((Number) firstValues.get("point-4010")).intValue());
                assertEquals(1, server.readRequestCount());

                ProcessResult firstResult = processResults.get("point-4001");
                assertNotNull(firstResult);
                assertEquals(100, ((Number) firstResult.getMetadata(ProcessResultMetadataKeys.RAW_VALUE)).intValue());
                assertEquals(21.0d,
                        ((Number) firstResult.getMetadata(ProcessResultMetadataKeys.PROCESSED_VALUE)).doubleValue(),
                        0.0001d);
                assertEquals("POLLING", firstResult.getMetadata(ProcessResultMetadataKeys.SOURCE));

                ProcessResult alarmResult = processResults.get("point-4010");
                assertTrue(alarmResult.getMetadata(AlarmMetadataKeys.EVENT_TRIGGERED, false));
                assertEquals("ALARM", alarmResult.getMetadata(AlarmMetadataKeys.EVENT_TYPE));
                assertEquals(LOCAL_DEVICE_ID, alarmResult.getMetadata("rawDeviceId"));

                List<TelemetryPostProcessContext> stageContexts = executePostProcessing(
                        points, firstValues, processResults);
                assertEquals(POINT_COUNT * TelemetryStageType.values().length, stageContexts.size());
                assertTrue(stageContexts.stream().allMatch(context -> LOCAL_DEVICE_ID.equals(context.deviceId())));
                assertTrue(stageContexts.stream()
                        .allMatch(context -> LOCAL_DEVICE_ID.equals(context.point().getDeviceId())));
                assertFalse(stageContexts.stream()
                        .anyMatch(context -> "cloud-project-pk/cloud-device-name".equals(context.deviceId())));

                server.incrementHoldingRegisters(START_REGISTER, START_REGISTER + POINT_COUNT - 1, 1);
                Map<String, Object> secondValues = collector.readPoints(points);
                assertEquals(21.2d, ((Number) secondValues.get("point-4001")).doubleValue(), 0.0001d);
                assertEquals(110, ((Number) secondValues.get("point-4010")).intValue());
                assertEquals(2, server.readRequestCount());

                assertTrue(collector.writePoint(points.get(1), 777));
                assertEquals(777, server.getHoldingRegister(4002));
                assertEquals(1, server.writeRequestCount());
            } finally {
                adapter.disconnect();
            }
        }
    }

    private void initializeRegisters(FakeModbusTcpServer server) {
        for (int index = 0; index < POINT_COUNT; index++) {
            server.setHoldingRegister(START_REGISTER + index, 100 + index);
        }
    }

    private DeviceInfo deviceInfo() {
        DeviceInfo deviceInfo = new DeviceInfo();
        deviceInfo.setDeviceId(LOCAL_DEVICE_ID);
        deviceInfo.setDeviceName("本地 Modbus 验收设备");
        deviceInfo.setProtocolType("MODBUS_TCP");
        deviceInfo.setConnectionType("MODBUS_TCP");
        deviceInfo.setCollectionInterval(1000);

        CloudTargetConfig cloudTarget = new CloudTargetConfig();
        cloudTarget.setEnabled(true);
        cloudTarget.setDeviceType(CloudDeviceType.SUB_DEVICE);
        cloudTarget.setProductKey("cloud-project-pk");
        cloudTarget.setDeviceName("cloud-device-name");
        deviceInfo.setCloudTarget(cloudTarget);
        return deviceInfo;
    }

    private DeviceConnection connection(int port) {
        DeviceConnection connection = new DeviceConnection();
        connection.setConnectionType("MODBUS_TCP");
        connection.setHost("127.0.0.1");
        connection.setPort(port);
        connection.setReadTimeout(3000);
        connection.setTimeout(3000);
        connection.setExtJson(Map.of(
                "slaveId", 1,
                "byteOrder", "BIG_ENDIAN",
                "maxRegistersPerRequest", 125
        ));
        return connection;
    }

    private List<DataPoint> points() {
        List<DataPoint> points = new ArrayList<>();
        for (int index = 0; index < POINT_COUNT; index++) {
            int register = START_REGISTER + index;
            DataPoint point = new DataPoint();
            point.setPointId("point-" + register);
            point.setPointCode("register_" + register);
            point.setPointName("保持寄存器 " + register);
            point.setDeviceId(LOCAL_DEVICE_ID);
            point.setDeviceName("本地 Modbus 验收设备");
            point.setAddress(toHoldingRegisterReference(register));
            point.setDataType("UINT16");
            point.setReadWrite(index == 1 ? "RW" : "R");
            point.setUnitId(1);
            point.setStatus(1);
            point.setCacheEnabled(1);
            point.setAdditionalConfig(Map.of(
                    "reportEnabled", true,
                    "reportField", "register_" + register
            ));
            points.add(point);
        }
        points.get(0).setScalingFactor(0.2d);
        points.get(0).setOffset(1.0d);
        points.get(POINT_COUNT - 1).setAlarmEnabled(1);
        points.get(POINT_COUNT - 1).setAlarmRule("""
                [{"ruleId":"high-value","ruleName":"高值告警","operator":">",
                  "threshold":108,"duration":0,"level":"WARNING",
                  "description":"寄存器值超过阈值","enabled":true}]
                """);
        return points;
    }

    private String toHoldingRegisterReference(int oneBasedRegister) {
        if (oneBasedRegister <= 0 || oneBasedRegister > 9999) {
            throw new IllegalArgumentException("验收寄存器地址必须在 1 到 9999 之间");
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
        collector.rebuildReadPlans(LOCAL_DEVICE_ID, points);
        return collector;
    }

    private List<TelemetryPostProcessContext> executePostProcessing(
            List<DataPoint> points,
            Map<String, Object> values,
            Map<String, ProcessResult> processResults) {
        List<TelemetryPostProcessContext> contexts = new CopyOnWriteArrayList<>();
        List<TelemetryPostProcessStage> stages = new ArrayList<>();
        for (TelemetryStageType stageType : TelemetryStageType.values()) {
            stages.add(new RecordingStage(stageType, contexts));
        }
        TelemetryPostProcessPipeline pipeline = new TelemetryPostProcessPipeline(
                stages,
                Runnable::run,
                Runnable::run,
                Runnable::run,
                Runnable::run);
        CollectorDataPostProcessor postProcessor = new CollectorDataPostProcessor(
                Runnable::run, pipeline, new CollectionTaskGuard());
        postProcessor.saveBatchAsync(LOCAL_DEVICE_ID, points, values, processResults);
        return contexts;
    }

    private record RecordingStage(
            TelemetryStageType type,
            List<TelemetryPostProcessContext> contexts) implements TelemetryPostProcessStage {

        @Override
        public String name() {
            return "验收记录阶段-" + type.name();
        }

        @Override
        public boolean enabled(TelemetryPostProcessContext context) {
            return true;
        }

        @Override
        public void process(TelemetryPostProcessContext context) {
            contexts.add(context);
        }
    }
}
