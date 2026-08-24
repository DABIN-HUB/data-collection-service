package com.wangbin.collector.core.collector.protocol.opc.da;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 定义当前模块的业务组件。
 */
public class InMemoryOpcDaBridge implements OpcDaBridge {

    private final Map<String, Object> itemValues = new ConcurrentHashMap<>();
    private final Set<String> subscribed = ConcurrentHashMap.newKeySet();
    private volatile boolean connected;

    /**
     * 处理连接生命周期。
     */
    @Override
    public void connect(OpcDaConfig config) {
        connected = true;
    }

    /**
     * 处理连接生命周期。
     */
    @Override
    public void disconnect() {
        connected = false;
        subscribed.clear();
    }

    /**
     * 查询并返回业务数据。
     */
    @Override
    public Object read(String itemId) {
        ensureConnected();
        return itemValues.get(itemId);
    }

    /**
     * 查询并返回业务数据。
     */
    @Override
    public Map<String, Object> readBatch(List<String> itemIds) {
        ensureConnected();
        Map<String, Object> values = new HashMap<>(itemIds.size());
        for (String itemId : itemIds) {
            values.put(itemId, itemValues.get(itemId));
        }
        return values;
    }

    /**
     * 写入或持久化业务数据。
     */
    @Override
    public boolean write(String itemId, Object value) {
        ensureConnected();
        itemValues.put(itemId, value);
        return true;
    }

    /**
     * 维护注册或订阅关系。
     */
    @Override
    public void subscribe(List<String> itemIds) {
        ensureConnected();
        subscribed.addAll(itemIds);
    }

    /**
     * 维护注册或订阅关系。
     */
    @Override
    public void unsubscribe(List<String> itemIds) {
        ensureConnected();
        subscribed.removeAll(itemIds);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public List<Map<String, Object>> browse(String branch) {
        ensureConnected();
        String prefix = branch == null ? "" : branch;
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Map.Entry<String, Object> entry : itemValues.entrySet()) {
            if (prefix.isEmpty() || entry.getKey().startsWith(prefix)) {
                nodes.add(Map.of(
                        "itemId", entry.getKey(),
                        "value", entry.getValue()
                ));
            }
        }
        return nodes;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    /**
     * 校验业务条件和参数边界。
     */
    private void ensureConnected() {
        if (!connected) {
            throw new IllegalStateException("OPC DA bridge is not connected");
        }
    }
}

