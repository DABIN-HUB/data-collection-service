package com.wangbin.collector.core.collector.telemetry;

import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.core.processor.ProcessResultMetadataKeys;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class CollectorTelemetryMetadataEnricherTest {

    private final CollectorTelemetryMetadataEnricher enricher = new CollectorTelemetryMetadataEnricher();

    @Test
    void shouldEnrichMissingGenericMetadata() {
        ProcessResult result = ProcessResult.success(1, 2);

        enricher.enrich(result, 10, 20, 123L, "POLLING");

        assertEquals(10, ((Number) result.getMetadata(ProcessResultMetadataKeys.RAW_VALUE)).intValue());
        assertEquals(20, ((Number) result.getMetadata(ProcessResultMetadataKeys.PROCESSED_VALUE)).intValue());
        assertEquals(123L, ((Number) result.getMetadata(ProcessResultMetadataKeys.COLLECT_TIME)).longValue());
        assertEquals("POLLING", result.getMetadata(ProcessResultMetadataKeys.SOURCE));
    }

    @Test
    void shouldNotOverrideExistingMetadata() {
        ProcessResult result = ProcessResult.success(1, 2);
        result.addMetadata(ProcessResultMetadataKeys.RAW_VALUE, "raw");
        result.addMetadata(ProcessResultMetadataKeys.PROCESSED_VALUE, "processed");
        result.addMetadata(ProcessResultMetadataKeys.COLLECT_TIME, 11L);
        result.addMetadata(ProcessResultMetadataKeys.SOURCE, "CUSTOM");

        enricher.enrich(result, 10, 20, 123L, "POLLING");

        assertEquals("raw", result.getMetadata(ProcessResultMetadataKeys.RAW_VALUE));
        assertEquals("processed", result.getMetadata(ProcessResultMetadataKeys.PROCESSED_VALUE));
        assertEquals(11L, ((Number) result.getMetadata(ProcessResultMetadataKeys.COLLECT_TIME)).longValue());
        assertEquals("CUSTOM", result.getMetadata(ProcessResultMetadataKeys.SOURCE));
    }

    @Test
    void shouldKeepNullAndNonPositiveCollectTimeBehavior() {
        ProcessResult result = ProcessResult.success(1, 2);

        enricher.enrich(result, null, null, 0L, null);

        assertFalse(result.getMetadata().containsKey(ProcessResultMetadataKeys.RAW_VALUE));
        assertFalse(result.getMetadata().containsKey(ProcessResultMetadataKeys.PROCESSED_VALUE));
        assertFalse(result.getMetadata().containsKey(ProcessResultMetadataKeys.COLLECT_TIME));
        assertFalse(result.getMetadata().containsKey(ProcessResultMetadataKeys.SOURCE));
    }

    @Test
    void shouldKeepNullResultAndNullMetadataBehavior() {
        enricher.enrich(null, 10, 20, 123L, "POLLING");

        ProcessResult result = ProcessResult.success(1, 2);
        result.setMetadata(null);

        enricher.enrich(result, 10, 20, 123L, "POLLING");

        assertNull(result.getMetadata());
    }

    @Test
    void shouldNotHoldRuntimeStateFields() {
        assertEquals(0, CollectorTelemetryMetadataEnricher.class.getDeclaredFields().length);
    }
}
