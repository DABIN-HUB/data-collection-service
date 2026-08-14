package com.wangbin.collector.core.collector.protocol.opc.da;

import java.util.List;
import java.util.Map;

/**
 * 定义当前模块的业务契约。
 */
public interface OpcDaBridge {
    /**
     * 处理连接生命周期。
     */
    void connect(OpcDaConfig config) throws Exception;

    /**
     * 处理连接生命周期。
     */
    void disconnect() throws Exception;

    /**
     * 查询并返回业务数据。
     */
    Object read(String itemId) throws Exception;

    /**
     * 查询并返回业务数据。
     */
    Map<String, Object> readBatch(List<String> itemIds) throws Exception;

    /**
     * 写入或持久化业务数据。
     */
    boolean write(String itemId, Object value) throws Exception;

    /**
     * 维护注册或订阅关系。
     */
    void subscribe(List<String> itemIds) throws Exception;

    /**
     * 维护注册或订阅关系。
     */
    void unsubscribe(List<String> itemIds) throws Exception;

    /**
     * 执行当前业务逻辑。
     */
    List<Map<String, Object>> browse(String branch) throws Exception;

    boolean isConnected();
}

