package com.wangbin.collector.storage.buffer;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.processor.ProcessResult;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 可回放的历史数据写入请求。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HistoryWriteRequest {

    private String deviceId;
    private String protocolType;
    private DataPoint point;
    private ProcessResult processResult;
    private long eventTs;
}
