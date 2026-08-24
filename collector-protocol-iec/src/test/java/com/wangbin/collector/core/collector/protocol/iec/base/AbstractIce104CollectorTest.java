package com.wangbin.collector.core.collector.protocol.iec.base;

import com.wangbin.collector.common.domain.entity.DataPoint;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbstractIce104CollectorTest {

    @Test
    void shouldKeepTypedCacheSeparateFromOtherTypes() {
        TestIec104Collector collector = new TestIec104Collector();

        ReflectionTestUtils.invokeMethod(collector, "cacheValue", 1, Integer.valueOf(13), 100, 12.5d);

        assertEquals(12.5d, collector.cachedValue(1, 13, 100));
        assertEquals(12.5d, collector.cachedValue(1, null, 100));
        assertEquals(null, collector.cachedValue(1, 3, 100));
    }

    @Test
    void shouldCompleteExactAndWildcardPendingRequestsWithoutCrossTypeWakeup() {
        TestIec104Collector collector = new TestIec104Collector();
        CompletableFuture<Object> typed = collector.pending(1, 13, 200);
        CompletableFuture<Object> wildcard = collector.pending(1, null, 200);
        CompletableFuture<Object> otherType = collector.pending(1, 3, 200);

        ReflectionTestUtils.invokeMethod(collector, "completeRequest", 1, Integer.valueOf(13), 200, 99.0d);

        assertTrue(typed.isDone());
        assertTrue(wildcard.isDone());
        assertFalse(otherType.isDone());
        assertEquals(99.0d, typed.getNow(null));
        assertEquals(99.0d, wildcard.getNow(null));

        ReflectionTestUtils.invokeMethod(collector, "completeRequest", 1, Integer.valueOf(3), 200, 11.0d);
        assertTrue(otherType.isDone());
        assertEquals(11.0d, otherType.getNow(null));
    }

    @Test
    void shouldUseConfiguredTimeoutForPendingRequests() {
        TestIec104Collector collector = new TestIec104Collector();
        ReflectionTestUtils.setField(collector, "timeout", 50);

        CompletableFuture<Object> future = collector.pending(1, 13, 300);
        ExecutionException executionException = assertThrows(
                ExecutionException.class,
                () -> future.get(1, TimeUnit.SECONDS));

        assertInstanceOf(TimeoutException.class, executionException.getCause());
    }

    @Test
    void shouldClearPendingRequestsOnProtocolReset() {
        TestIec104Collector collector = new TestIec104Collector();
        CompletableFuture<Object> future = collector.pending(1, 13, 301);

        collector.resetProtocolState();

        assertTrue(future.isCompletedExceptionally());
        assertEquals(0, collector.pendingRequestSize());
    }

    private static final class TestIec104Collector extends AbstractIce104Collector {

        private CompletableFuture<Object> pending(int commonAddress, Integer typeId, int ioa) {
            return registerPendingRequest(commonAddress, typeId, ioa);
        }

        private Object cachedValue(int commonAddress, Integer typeId, int ioa) {
            return getCachedValue(commonAddress, typeId, ioa);
        }

        private void resetProtocolState() {
            clearProtocolState();
        }

        private int pendingRequestSize() {
            return pendingRequests.size();
        }

        @Override
        protected void doConnect() {
        }

        @Override
        public void doDisconnect() {
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
            return "TEST_IEC104";
        }

        @Override
        public String getProtocolType() {
            return "IEC104";
        }
    }
}
