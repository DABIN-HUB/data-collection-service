package com.wangbin.collector.core.cache.realtime;

import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.core.processor.ProcessResultMetadataKeys;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealtimeChangeTrackerTest {

    @Test
    void plainSameValueShouldNotIncreaseRevisionTwice() {
        RealtimeChangeTracker tracker = new RealtimeChangeTracker("snapshot-test");

        assertTrue(tracker.record("dev-a", "p1", 25));
        assertFalse(tracker.record("dev-a", "p1", 25));

        assertEquals(1L, tracker.currentRevision());
        assertEquals(1, tracker.trackedPointCount());
    }

    @Test
    void plainChangedValueShouldIncreaseRevision() {
        RealtimeChangeTracker tracker = new RealtimeChangeTracker("snapshot-test");

        assertTrue(tracker.record("dev-a", "p1", 25));
        assertTrue(tracker.record("dev-a", "p1", 26));

        assertEquals(2L, tracker.currentRevision());
    }

    @Test
    void processResultSameSemanticStateShouldNotIncreaseRevision() {
        RealtimeChangeTracker tracker = new RealtimeChangeTracker("snapshot-test");

        assertTrue(tracker.record("dev-a", "p1", result(25, 100, 7L, 1000L)));
        assertFalse(tracker.record("dev-a", "p1", result(25, 100, 7L, 1000L)));

        assertEquals(1L, tracker.currentRevision());
    }

    @Test
    void collectTimeChangeShouldIncreaseRevision() {
        RealtimeChangeTracker tracker = new RealtimeChangeTracker("snapshot-test");

        assertTrue(tracker.record("dev-a", "p1", result(25, 100, 7L, 1000L)));
        assertTrue(tracker.record("dev-a", "p1", result(25, 100, 7L, 2000L)));

        assertEquals(2L, tracker.currentRevision());
    }

    @Test
    void collectionArrayAndMapFingerprintShouldBeContentAware() {
        RealtimeChangeTracker tracker = new RealtimeChangeTracker("snapshot-test");
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("bytes", new byte[]{1, 2, 3});
        first.put("items", List.of("a", 1));
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("items", List.of("a", 1));
        second.put("bytes", new byte[]{1, 2, 3});
        Map<String, Object> changed = new LinkedHashMap<>();
        changed.put("bytes", new byte[]{1, 2, 4});
        changed.put("items", List.of("a", 1));

        assertTrue(tracker.record("dev-a", "p1", first));
        assertFalse(tracker.record("dev-a", "p1", second));
        assertTrue(tracker.record("dev-a", "p1", changed));

        assertEquals(2L, tracker.currentRevision());
    }

    @Test
    void findChangedKeysShouldRespectDeviceScopeAndRevisionWindow() {
        RealtimeChangeTracker tracker = new RealtimeChangeTracker("snapshot-test");
        tracker.record("dev-a", "p1", 10);
        tracker.record("dev-b", "p2", 20);
        long upperRevision = tracker.currentRevision();
        tracker.record("dev-a", "p3", 30);

        List<RealtimeChangeTracker.PointKey> keys = tracker.findChangedKeys(0, upperRevision, "dev-a", 10);

        assertEquals(List.of(new RealtimeChangeTracker.PointKey("dev-a", "p1")), keys);
    }

    @Test
    void configInvalidateShouldAdvanceEpochAndClearPointStatesWithoutChangingSnapshotId() {
        RealtimeChangeTracker tracker = new RealtimeChangeTracker("snapshot-test");
        tracker.record("dev-a", "p1", 10);

        tracker.invalidateConfiguration();

        assertEquals("snapshot-test", tracker.snapshotId());
        assertEquals(2L, tracker.configEpoch());
        assertEquals(0, tracker.trackedPointCount());
    }

    @Test
    void cursorValidationShouldResetOnSnapshotConfigOrInvalidRevision() {
        RealtimeChangeTracker tracker = new RealtimeChangeTracker("snapshot-test");
        RealtimeChangeTracker.SnapshotCursor boundary = tracker.capture();

        assertEquals("SNAPSHOT_MISMATCH", tracker.validateCursor(boundary, "wrong", boundary.configEpoch(), boundary.revision()).resetReason());
        assertEquals("CONFIG_CHANGED", tracker.validateCursor(boundary, "snapshot-test", boundary.configEpoch() + 1, boundary.revision()).resetReason());
        assertEquals("CURSOR_INVALID", tracker.validateCursor(boundary, "snapshot-test", boundary.configEpoch(), boundary.revision() + 1).resetReason());
        assertTrue(tracker.validateCursor(boundary, "snapshot-test", boundary.configEpoch(), boundary.revision()).valid());
    }

    @Test
    void boundaryCurrentShouldIgnoreRevisionButRespectSnapshotAndEpoch() {
        RealtimeChangeTracker tracker = new RealtimeChangeTracker("snapshot-test");
        RealtimeChangeTracker.SnapshotCursor boundary = tracker.capture();

        assertTrue(tracker.isBoundaryCurrent(boundary));

        tracker.record("dev-a", "p1", 10);
        assertTrue(tracker.isBoundaryCurrent(boundary));

        assertFalse(tracker.isBoundaryCurrent(new RealtimeChangeTracker.SnapshotCursor("wrong-snapshot", boundary.configEpoch(), boundary.revision())));

        tracker.invalidateConfiguration();
        assertFalse(tracker.isBoundaryCurrent(boundary));
    }

    private ProcessResult result(Object value, int quality, long processingTime, long collectTime) {
        ProcessResult result = ProcessResult.success(value, value);
        result.setQuality(quality);
        result.setQualityDescription("正常");
        result.setProcessingTime(processingTime);
        result.addMetadata(ProcessResultMetadataKeys.COLLECT_TIME, collectTime);
        return result;
    }
}
