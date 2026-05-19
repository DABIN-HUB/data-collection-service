package com.wangbin.collector.core.collector.protocol.base;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.processor.DataQualityProcessor;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaseCollectorReadPointsTest {

    @Test
    void shouldKeepBatchResultWhenOnePointHasNullRawValue() throws Exception {
        Map<String, Object> rawValues = new HashMap<>();
        rawValues.put("p1", 12);

        TestCollector collector = new TestCollector(rawValues);
        DeviceInfo deviceInfo = new DeviceInfo();
        deviceInfo.setDeviceId("dev-1");
        deviceInfo.setDeviceName("test-device");
        collector.init(deviceInfo);
        collector.connected = true;
        collector.dataQualityProcessor = new DataQualityProcessor(null);

        Map<String, Object> result = collector.readPoints(List.of(point("p1"), point("p2")));

        assertEquals(12.0, result.get("p1"));
        assertTrue(result.containsKey("p2"));
        assertNull(result.get("p2"));
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

        private TestCollector(Map<String, Object> rawValues) {
            this.rawValues = rawValues;
        }

        @Override
        protected void doConnect() {
        }

        @Override
        protected void doDisconnect() {
        }

        @Override
        protected Object doReadPoint(DataPoint point) {
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
