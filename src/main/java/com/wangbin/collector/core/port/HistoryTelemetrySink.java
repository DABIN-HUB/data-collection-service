package com.wangbin.collector.core.port;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.processor.ProcessResult;

/**
 * 历史遥测写入端口，隔离 core 与存储实现。
 */
public interface HistoryTelemetrySink {

    /**
     * 历史写入功能是否启用。
     */
    boolean isEnabled();

    /**
     * 保存单点遥测处理结果。
     */
    void savePoint(String deviceId, DataPoint point, ProcessResult processResult);
}
