package com.wangbin.collector.core.collector.protocol.opc.da;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryOpcDaBridge implements OpcDaBridge {

    private final Map<String, Object> itemValues = new ConcurrentHashMap<>();
    private final Set<String> subscribed = ConcurrentHashMap.newKeySet();
    private volatile boolean connected;

    @Override
    public void connect(OpcDaConfig config) {
        connected = true;
    }

    @Override
    public void disconnect() {
        connected = false;
        subscribed.clear();
    }

    @Override
    public Object read(String itemId) {
        ensureConnected();
        return itemValues.get(itemId);
    }

    @Override
    public Map<String, Object> readBatch(List<String> itemIds) {
        ensureConnected();
        Map<String, Object> values = new HashMap<>(itemIds.size());
        for (String itemId : itemIds) {
            values.put(itemId, itemValues.get(itemId));
        }
        return values;
    }

    @Override
    public boolean write(String itemId, Object value) {
        ensureConnected();
        itemValues.put(itemId, value);
        return true;
    }

    @Override
    public void subscribe(List<String> itemIds) {
        ensureConnected();
        subscribed.addAll(itemIds);
    }

    @Override
    public void unsubscribe(List<String> itemIds) {
        ensureConnected();
        subscribed.removeAll(itemIds);
    }

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

    private void ensureConnected() {
        if (!connected) {
            throw new IllegalStateException("OPC DA bridge is not connected");
        }
    }
}

