package com.wangbin.collector.core.port;

/**
 * 系统资源探测端口，仅暴露调度器需要的运行指标。
 */
public interface SystemResourceProbe {

    /**
     * 返回当前进程 CPU 使用率百分比，无法读取时返回负数。
     */
    double getProcessCpuLoad();
}
