package com.wangbin.collector.core.collector.protocol.base;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.port.ExceptionReporter;
import com.wangbin.collector.core.processor.ProcessResult;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BaseCollectorReadPointsTest {

    @Test
    void shouldSkipMissingRawValueWithoutBreakingBatch() throws Exception {
        Map<String, Object> rawValues = new HashMap<>();
        rawValues.put("p1", 12);

        TestCollector collector = new TestCollector(rawValues);
        DeviceInfo deviceInfo = new DeviceInfo();
        deviceInfo.setDeviceId("dev-1");
        deviceInfo.setDeviceName("test-device");
        collector.init(deviceInfo);
        collector.connected = true;
        collector.dataQualityProcessor = com.wangbin.collector.core.processor.DataQualityProcessorTestSupport.create();

        Map<String, Object> result = collector.readPoints(List.of(point("p1"), point("p2")));

        assertEquals(12.0, result.get("p1"));
        assertEquals(false, result.containsKey("p2"));
    }

    @Test
    void shouldTrackSubscribedPointCount() throws Exception {
        TestCollector collector = connectedCollector(new TestCollector(Map.of()));

        collector.subscribe(List.of(point("p1"), point("p2")));

        assertEquals(2, collector.getDeviceStatus().get("subscribedPoints"));
        assertEquals(2, collector.getStatistics().get("subscribedPoints"));

        collector.unsubscribe(List.of(point("p1")));

        assertEquals(1, collector.getDeviceStatus().get("subscribedPoints"));
        assertEquals(1, collector.getStatistics().get("subscribedPoints"));
    }

    @Test
    void shouldKeepProcessResultSnapshotBoundToEachCollectionRound() throws Exception {
        Map<String, Object> rawValues = new HashMap<>();
        rawValues.put("p1", 10);
        TestCollector collector = connectedCollector(new TestCollector(rawValues));

        collector.readPoints(List.of(point("p1")));
        Map<String, ProcessResult> firstRound = collector.takeInvocationProcessResults();
        rawValues.put("p1", 20);
        collector.readPoints(List.of(point("p1")));
        Map<String, ProcessResult> secondRound = collector.takeInvocationProcessResults();

        assertEquals(10.0D, firstRound.get("p1").getFinalValue());
        assertEquals(20.0D, secondRound.get("p1").getFinalValue());
    }

    @Test
    void disconnectShouldStillCloseWhenUnsubscribeFails() throws Exception {
        TestCollector collector = connectedCollector(new TestCollector(Map.of()));
        collector.subscribe(List.of(point("p1")));
        collector.failUnsubscribe = true;

        collector.disconnect();

        assertEquals(1, collector.disconnectCount);
        assertEquals(false, collector.isConnected());
        assertEquals(0, collector.getStatistics().get("subscribedPoints"));
    }

    @Test
    void executeCommandShouldAcceptNumericStringAndLongSlaveId() throws Exception {
        TestCollector collector = connectedCollector(new TestCollector(Map.of()));

        Object stringResult = collector.executeCommand("read", Map.of("slaveId", "2"));
        Object longResult = collector.executeCommand("read", Map.of("slaveId", 3L));

        assertEquals(2, stringResult);
        assertEquals(3, longResult);
    }

    @Test
    void shouldReportReadExceptionThroughExceptionReporterPort() throws Exception {
        TestCollector collector = connectedCollector(new TestCollector(Map.of()));
        ExceptionReporter exceptionReporter = mock(ExceptionReporter.class);
        collector.exceptionReporter = exceptionReporter;
        collector.failReadPoint = true;
        DataPoint point = point("p1");

        assertThrows(CollectorException.class, () -> collector.readPoint(point));

        verify(exceptionReporter).record(collector.readFailure, "dev-1", "p1");
    }

    private TestCollector connectedCollector(TestCollector collector) throws Exception {
        DeviceInfo deviceInfo = new DeviceInfo();
        deviceInfo.setDeviceId("dev-1");
        deviceInfo.setDeviceName("test-device");
        collector.init(deviceInfo);
        collector.connected = true;
        collector.dataQualityProcessor = com.wangbin.collector.core.processor.DataQualityProcessorTestSupport.create();
        return collector;
    }

    private DataPoint point(String pointId) {
        DataPoint point = new DataPoint();
        point.setPointId(pointId);
        point.setPointName(pointId);
        point.setDeviceId("dev-1");
        point.setStatus(1);
        return point;
    }

    private static final class TestCollector extends BaseCollector {

        private final Map<String, Object> rawValues;
        private boolean failUnsubscribe;
        private boolean failReadPoint;
        private final RuntimeException readFailure = new RuntimeException("read failed");
        private int disconnectCount;

        private TestCollector(Map<String, Object> rawValues) {
            this.rawValues = rawValues;
        }

        @Override
        protected void doConnect() {
        }

        @Override
        protected void doDisconnect() {
            disconnectCount++;
        }

        @Override
        protected Object doReadPoint(DataPoint point) {
            if (failReadPoint) {
                throw readFailure;
            }
            return rawValues.get(point.getPointId());
        }

        @Override
        protected Map<String, Object> doReadPoints(List<DataPoint> points) {
            return rawValues;
        }

        @Override
        protected boolean doWritePoint(DataPoint point, Object value) {
            return false;
        }

        @Override
        protected Map<String, Boolean> doWritePoints(Map<DataPoint, Object> points) {
            return Map.of();
        }

        @Override
        protected void doSubscribe(List<DataPoint> points) {
        }

        @Override
        protected void doUnsubscribe(List<DataPoint> points) {
            if (failUnsubscribe) {
                throw new IllegalStateException("unsubscribe failed");
            }
        }

        @Override
        protected Map<String, Object> doGetDeviceStatus() {
            return Map.of();
        }

        @Override
        protected Object doExecuteCommand(int unitId, String command, Map<String, Object> params) {
            return unitId;
        }

        @Override
        protected void buildReadPlans(String deviceId, List<DataPoint> points) {
        }

        @Override
        public String getCollectorType() {
            return "TEST";
        }

        @Override
        public String getProtocolType() {
            return "TEST";
        }
    }
}
