package com.wangbin.collector.monitor.network;

/**
 * 网络检测方式。
 */
public enum NetworkDiagnosticType {
    /** 网络可达性检测。 */
    PING,
    /** 路由跟踪检测。 */
    TRACE,
    /** TCP 端口连接检测。 */
    TCP
}