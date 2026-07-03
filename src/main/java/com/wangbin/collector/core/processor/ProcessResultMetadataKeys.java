package com.wangbin.collector.core.processor;

/**
 * Metadata keys shared by collectors and downstream persistence builders.
 */
public final class ProcessResultMetadataKeys {

    public static final String RAW_VALUE = "collectorRawValue";
    public static final String PROCESSED_VALUE = "collectorProcessedValue";
    public static final String RAW_BYTES = "rawBytes";
    public static final String COLLECT_TIME = "collectTime";
    public static final String SOURCE = "source";
    public static final String COLLECTOR_ID = "collectorId";
    public static final String BATCH_ID = "batchId";
    public static final String GROUP_ID = "groupId";
    public static final String PROCESSING_VERSION = "processingVersion";

    private ProcessResultMetadataKeys() {
    }
}
