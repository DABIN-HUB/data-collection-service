package com.wangbin.collector.core.report.shadow;

/**
 * 属性值元数据
 */
public class ValueMeta {

    private final Object value;
    private final long timestamp;
    private final String quality;
    private final String source;
    private final long updatedAt;

    public ValueMeta(Object value, long timestamp, String quality) {
        this(value, timestamp, quality, null);
    }

    public ValueMeta(Object value, long timestamp, String quality, String source) {
        this(value, timestamp, quality, source, System.currentTimeMillis());
    }

    public ValueMeta(Object value, long timestamp, String quality, String source, long updatedAt) {
        this.value = value;
        this.timestamp = timestamp;
        this.quality = quality;
        this.source = source;
        this.updatedAt = updatedAt;
    }

    public Object getValue() {
        return value;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getQuality() {
        return quality;
    }

    public String getSource() {
        return source;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }
}
