package com.wangbin.collector.core.collector.edge;

/**
 * 独立边缘进程可上送的数据源类型，不代表当前 Java 服务直接实现了现场总线主站。
 */
public enum EdgeProtocolType {
    PROFINET,
    ETHERCAT,
    GENERIC_EDGE
}
