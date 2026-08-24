package com.wangbin.collector.common.domain.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 数据上报消息
 */
@Data
public class ReportMessage {

    private String reportId;
    private Long timestamp;
    private String reportType; // 上报类型，例如实时、历史或告警。
    private String source; // 数据来源，例如采集器、处理器或缓存。
    private List<DataItem> dataItems;
    private Map<String, Object> metadata;

    /**
     * 定义当前模块的业务组件。
     */
    @Data
    public static class DataItem {
        private String pointId;
        private String deviceId;
        private String pointName;
        private String pointAlias;
        private Object value;
        private Object rawValue;
        private String dataType;
        private Integer quality;
        private String unit;
        private Long collectTime;
        private Long processTime;
        private Map<String, Object> tags;

        /**
         * 创建当前组件实例。
         */
        public DataItem() {
            this.collectTime = System.currentTimeMillis();
            this.processTime = System.currentTimeMillis();
            this.quality = 100;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    public void addDataItem(DataItem item) {
        if (dataItems == null) {
            dataItems = new java.util.ArrayList<>();
        }
        dataItems.add(item);
    }

    /**
     * 执行当前业务逻辑。
     */
    public void addMetadata(String key, Object value) {
        if (metadata == null) {
            metadata = new java.util.HashMap<>();
        }
        metadata.put(key, value);
    }
}
