package com.wangbin.collector.core.collector.protocol.base;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.runtime.SubscriptionFallbackStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BaseCollectorSubscriptionFallbackTest {

    @Test
    void shouldExposePollingFallbackWithoutRegisteringFalseSubscription() throws Exception {
        FallbackCollector collector = new FallbackCollector();
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("fallback-device");
        device.setDeviceName("订阅降级测试设备");
        collector.init(device);
        collector.markConnected();

        DataPoint point = new DataPoint();
        point.setPointId("point-1");
        collector.subscribe(List.of(point));

        Map<String, Object> status = collector.getDeviceStatus();
        assertEquals("SUBSCRIPTION", status.get("requestedSubscriptionMode"));
        assertEquals("POLLING", status.get("actualSubscriptionMode"));
        assertEquals(1, status.get("subscriptionFallbackPointCount"));
        assertEquals(0, status.get("subscribedPoints"));
        assertEquals("当前驱动不支持订阅", status.get("subscriptionDegradedReason"));
        assertNull(status.get("collectorSpecificStatus"));
    }

    private static class FallbackCollector extends BaseCollector {

        private void markConnected() {
            connected = true;
        }

        @Override
        protected SubscriptionFallbackStrategy resolveSubscriptionFallbackStrategy() {
            return SubscriptionFallbackStrategy.FALLBACK_TO_POLLING;
        }

        @Override
        public String getCollectorType() {
            return "TEST";
        }

        @Override
        public String getProtocolType() {
            return "TEST";
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
        protected Map<String, Object> doReadPoints(List<DataPoint> points) {
            return Map.of();
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
            throw new UnsupportedOperationException("当前驱动不支持订阅");
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
    }
}
