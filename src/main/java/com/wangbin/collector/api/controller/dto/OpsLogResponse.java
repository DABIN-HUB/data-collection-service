package com.wangbin.collector.api.controller.dto;

import com.wangbin.collector.monitor.log.OperationLogger;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 运维日志查询响应数据。
 */
@Data
@Builder
public class OpsLogResponse {

    /**
     * 内存缓冲中的日志总数。
     */
    private Integer totalBuffered;

    /**
     * 本次返回日志数量。
     */
    private Integer count;

    /**
     * 日志明细列表。
     */
    private List<OperationLogger.OperationLogEntry> items;
}
