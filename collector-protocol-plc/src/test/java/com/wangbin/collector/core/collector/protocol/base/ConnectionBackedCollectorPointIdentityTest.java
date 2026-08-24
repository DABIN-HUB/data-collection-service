package com.wangbin.collector.core.collector.protocol.base;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.s7.S7Collector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConnectionBackedCollectorPointIdentityTest {

    @Test
    void shouldResolveCacheKeyWithStableFallbackOrder() {
        TestableS7Collector collector = new TestableS7Collector();
        DataPoint point = new DataPoint();
        point.setAddress("DB1.DBW2:INT");
        point.setPointCode("temperature");

        assertEquals("DB1.DBW2:INT", collector.resolveCacheKey(point));

        point.setPointId("p-1");
        assertEquals("p-1", collector.resolveCacheKey(point));
    }

    @Test
    void shouldResolveTagNameFromPointIdOrFallbackCacheKey() {
        TestableS7Collector collector = new TestableS7Collector();
        DataPoint point = new DataPoint();
        point.setAddress("DB1.DBX0.0:BOOL");

        assertEquals("DB1.DBX0.0:BOOL", collector.resolveTag(point));

        point.setPointId("point-1");
        assertEquals("point-1", collector.resolveTag(point));
    }

    @Test
    void shouldRejectUnresolvableCacheKey() {
        TestableS7Collector collector = new TestableS7Collector();
        DataPoint point = new DataPoint();

        assertThrows(IllegalArgumentException.class, () -> collector.resolveCacheKey(point));
    }

    private static final class TestableS7Collector extends S7Collector {
        private String resolveCacheKey(DataPoint point) {
            return resolvePointCacheKey(point);
        }

        private String resolveTag(DataPoint point) {
            return resolvePointTagName(point);
        }
    }
}