package com.wangbin.collector.core.report.outbox;

import com.wangbin.collector.core.report.model.ReportData;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 云端上报持久化消息，云身份在创建后不得随设备配置变化。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CloudOutboxMessage {

    private String messageId;
    private String localDeviceId;
    private String productKey;
    private String deviceName;
    private String gatewayDeviceId;
    private long shadowVersion;
    private long windowStart;
    private long windowEnd;
    private long createdAt;
    private long nextAttemptAt;
    private int attempts;
    private CloudOutboxStatus status;
    private String lastError;
    private ReportDataSnapshot reportData;
    private List<CloudOutboxCommit> commits;

    /**
     * 解析或转换业务数据。
     */
    public ReportData toReportData() {
        return reportData == null ? null : reportData.toReportData();
    }

    /**
     * 解析或转换业务数据。
     */
    public List<CloudOutboxCommit> resolveCommits() {
        if (commits != null && !commits.isEmpty()) {
            return List.copyOf(commits);
        }
        Map<String, Object> properties = reportData == null
                ? Map.of() : new LinkedHashMap<>(reportData.getProperties());
        return List.of(new CloudOutboxCommit(
                localDeviceId, shadowVersion, windowStart, windowEnd, properties));
    }

    /**
     * 单个本地设备的影子提交信息。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CloudOutboxCommit {
        private String localDeviceId;
        private long shadowVersion;
        private long windowStart;
        private long windowEnd;
        private Map<String, Object> properties = new LinkedHashMap<>();
    }

    /**
     * 可稳定序列化的上报数据快照。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReportDataSnapshot {

        private String deviceId;
        private String pointCode;
        private String pointId;
        private String pointName;
        private Object value;
        private long timestamp;
        private String method;
        private String quality;
        private Map<String, Object> metadata = new LinkedHashMap<>();
        private Map<String, Object> properties = new LinkedHashMap<>();
        private Map<String, String> propertyQuality = new LinkedHashMap<>();
        private Map<String, Long> propertyTs = new LinkedHashMap<>();
        private Map<String, Map<String, Object>> propertyMetadata = new LinkedHashMap<>();
        private Map<String, Object> events = new LinkedHashMap<>();

        /**
         * 创建并返回业务对象。
         */
        public static ReportDataSnapshot from(ReportData data) {
            ReportDataSnapshot snapshot = new ReportDataSnapshot();
            snapshot.deviceId = data.getDeviceId();
            snapshot.pointCode = data.getPointCode();
            snapshot.pointId = data.getPointId();
            snapshot.pointName = data.getPointName();
            snapshot.value = data.getValue();
            snapshot.timestamp = data.getTimestamp();
            snapshot.method = data.getMethod();
            snapshot.quality = data.getQuality();
            snapshot.metadata.putAll(data.getMetadata());
            snapshot.properties.putAll(data.getProperties());
            snapshot.propertyQuality.putAll(data.getPropertyQuality());
            snapshot.propertyTs.putAll(data.getPropertyTs());
            data.getPropertyMetadata().forEach((key, value) ->
                    snapshot.propertyMetadata.put(key, new LinkedHashMap<>(value)));
            snapshot.events.putAll(data.getEvents());
            return snapshot;
        }

        /**
         * 解析或转换业务数据。
         */
        public ReportData toReportData() {
            ReportData data = new ReportData();
            data.setDeviceId(deviceId);
            data.setPointCode(pointCode);
            data.setPointId(pointId);
            data.setPointName(pointName);
            data.setValue(value);
            data.setTimestamp(timestamp);
            data.setMethod(method);
            data.setQuality(quality);
            data.getMetadata().putAll(metadata);
            properties.forEach((field, fieldValue) -> data.addProperty(
                    field,
                    fieldValue,
                    propertyTs.getOrDefault(field, timestamp),
                    propertyQuality.get(field),
                    propertyMetadata.get(field)));
            events.forEach((identifier, eventValue) ->
                    data.addEvent(identifier, eventValue, timestamp));
            return data;
        }
    }
}
