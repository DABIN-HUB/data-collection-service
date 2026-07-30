package com.wangbin.collector.core.collector.protocol.opc.da;

import java.util.List;
import java.util.Map;

public interface OpcDaBridge {
    void connect(OpcDaConfig config) throws Exception;

    void disconnect() throws Exception;

    Object read(String itemId) throws Exception;

    Map<String, Object> readBatch(List<String> itemIds) throws Exception;

    boolean write(String itemId, Object value) throws Exception;

    void subscribe(List<String> itemIds) throws Exception;

    void unsubscribe(List<String> itemIds) throws Exception;

    List<Map<String, Object>> browse(String branch) throws Exception;

    boolean isConnected();
}

