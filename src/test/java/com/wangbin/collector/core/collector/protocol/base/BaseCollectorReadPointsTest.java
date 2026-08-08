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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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

    @Test
    void shouldKeepProtectedConversionFacadesDelegatingToConverter() throws Exception {
        TestCollector collector = connectedCollector(new TestCollector(Map.of()));
        DataPoint point = point("p1");
        point.setScalingFactor(2.0D);
        point.setOffset(1.0D);
        point.setMinValue(0.0D);
        point.setMaxValue(10.0D);

        assertEquals(7.0D, collector.exposeConvertData(point, 3));
        assertEquals(2.0D, collector.exposeConvertDataForWrite(point, 6));
        assertDoesNotThrow(() -> collector.exposeValidateData(point, 7));
    }

    @Test
    void readPointShouldUseProtectedTelemetryMetadataFacade() throws Exception {
        MetadataOverrideCollector collector = connectedCollector(new MetadataOverrideCollector(Map.of("p1", 7)));

        assertEquals(7.0D, collector.readPoint(point("p1")));

        assertEquals(1, collector.enrichCount);
        assertEquals(7, collector.rawValue);
        assertEquals(7.0D, collector.processedValue);
        assertEquals("POLLING", collector.source);
        assertTrue(collector.collectTime > 0);
    }

    @Test
    void ingestPushedValueShouldUseProtectedTelemetryMetadataFacade() throws Exception {
        MetadataOverrideCollector collector = connectedCollector(new MetadataOverrideCollector(Map.of()));

        ProcessResult result = collector.exposeIngestPushedValue(point("p1"), 8);

        assertEquals(8.0D, result.getFinalValue());
        assertEquals(1, collector.enrichCount);
        assertEquals(8, collector.rawValue);
        assertEquals(8.0D, collector.processedValue);
        assertEquals("PUSH", collector.source);
        assertTrue(collector.collectTime > 0);
    }

    @Test
    void writePointShouldUseSubclassConvertDataForWriteOverride() throws Exception {
        OverrideWriteCollector collector = connectedCollector(new OverrideWriteCollector(Map.of()));
        DataPoint point = point("p1");
        point.setReadWrite("W");

        assertTrue(collector.writePoint(point, 12));

        assertTrue(collector.overrideCalled);
        assertEquals("OVERRIDDEN", collector.writtenValue);
    }

    private <T extends TestCollector> T connectedCollector(T collector) throws Exception {
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

    private static class TestCollector extends BaseCollector {

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

        Object exposeConvertData(DataPoint point, Object rawValue) {
            return convertData(point, rawValue);
        }

        Object exposeConvertDataForWrite(DataPoint point, Object value) {
            return convertDataForWrite(point, value);
        }

        void exposeValidateData(DataPoint point, Object value) {
            validateData(point, value);
        }
    }

    private static class MetadataOverrideCollector extends TestCollector {

        private int enrichCount;
        private Object rawValue;
        private Object processedValue;
        private long collectTime;
        private String source;

        private MetadataOverrideCollector(Map<String, Object> rawValues) {
            super(rawValues);
        }

        @Override
        protected void enrichTelemetryMetadata(ProcessResult result,
                                               Object rawValue,
                                               Object processedValue,
                                               long collectTime,
                                               String source) {
            enrichCount++;
            this.rawValue = rawValue;
            this.processedValue = processedValue;
            this.collectTime = collectTime;
            this.source = source;
            super.enrichTelemetryMetadata(result, rawValue, processedValue, collectTime, source);
        }

        ProcessResult exposeIngestPushedValue(DataPoint point, Object rawValue) {
            return ingestPushedValue(point, rawValue);
        }
    }

    private static class OverrideWriteCollector extends TestCollector {

        private boolean overrideCalled;
        private Object writtenValue;

        private OverrideWriteCollector(Map<String, Object> rawValues) {
            super(rawValues);
        }

        @Override
        protected Object convertDataForWrite(DataPoint point, Object value) {
            overrideCalled = true;
            return "OVERRIDDEN";
        }

        @Override
        protected boolean doWritePoint(DataPoint point, Object value) {
            writtenValue = value;
            return true;
        }
    }
}
