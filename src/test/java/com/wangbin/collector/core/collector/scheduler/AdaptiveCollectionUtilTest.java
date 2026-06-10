package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.common.domain.entity.DataPoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdaptiveCollectionUtilTest {

    @Test
    void shouldInitializeMissingAdaptiveCollectionConfigWithDefaults() {
        DataPoint point = new DataPoint();
        point.setPointId("p1");

        AdaptiveCollectionUtil.initDataPointAdaptiveConfig(point);

        assertEquals(AdaptiveCollectionUtil.DEFAULT_BASE_COLLECTION_INTERVAL,
                point.getBaseCollectionInterval());
        assertEquals(AdaptiveCollectionUtil.DEFAULT_BASE_COLLECTION_INTERVAL,
                point.getCurrentCollectionInterval());
        assertEquals(AdaptiveCollectionUtil.DEFAULT_MIN_COLLECTION_INTERVAL,
                point.getMinCollectionInterval());
        assertEquals(AdaptiveCollectionUtil.DEFAULT_MAX_COLLECTION_INTERVAL,
                point.getMaxCollectionInterval());
        assertEquals(AdaptiveCollectionUtil.DEFAULT_CHANGE_THRESHOLD,
                point.getPointChangeThreshold());
    }

    @Test
    void shouldClampBaseIntervalBetweenMinAndMax() {
        DataPoint point = new DataPoint();
        point.setPointId("p1");
        point.setBaseCollectionInterval(10_000L);
        point.setMinCollectionInterval(100L);
        point.setMaxCollectionInterval(1_000L);
        point.setPointChangeThreshold(2.0);

        AdaptiveCollectionUtil.initDataPointAdaptiveConfig(point);

        assertEquals(1_000L, point.getBaseCollectionInterval());
        assertEquals(1_000L, point.getCurrentCollectionInterval());
        assertEquals(100L, point.getMinCollectionInterval());
        assertEquals(1_000L, point.getMaxCollectionInterval());
        assertEquals(2.0, point.getPointChangeThreshold());
    }
}
