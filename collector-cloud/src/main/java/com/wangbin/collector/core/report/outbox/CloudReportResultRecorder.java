package com.wangbin.collector.core.report.outbox;

/**
 * 云上报终态记录端口。
 *
 * <p>云上报模块只依赖该窄接口，不直接依赖 TDengine 或其他存储实现。</p>
 */
@FunctionalInterface
public interface CloudReportResultRecorder {

    /**
     * 记录已达到业务终态的非敏感上报元数据。
     *
     * @param result 上报终态结果
     */
    void record(CloudReportResult result);
}
