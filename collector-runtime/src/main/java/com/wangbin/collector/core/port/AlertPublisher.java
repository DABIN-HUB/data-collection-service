package com.wangbin.collector.core.port;

import com.wangbin.collector.common.domain.alert.AlertNotification;

/**
 * 告警发布端口，隔离 core 与告警监控实现。
 */
public interface AlertPublisher {

    /**
     * 发布告警通知，并按调用方语义决定是否直接上传云端。
     */
    void notifyAlert(AlertNotification notification, boolean uploadToCloud);
}
