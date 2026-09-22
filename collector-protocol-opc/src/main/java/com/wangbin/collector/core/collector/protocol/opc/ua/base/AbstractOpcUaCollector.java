package com.wangbin.collector.core.collector.protocol.opc.ua.base;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.protocol.base.ConnectionBackedCollector;
import com.wangbin.collector.core.collector.protocol.opc.ua.domain.OpcUaAddress;
import com.wangbin.collector.core.collector.protocol.opc.ua.util.OpcUaAddressParser;
import com.wangbin.collector.core.connection.adapter.ConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.OpcUaConnectionAdapter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.subscriptions.MonitoredItemServiceOperationResult;
import org.eclipse.milo.opcua.sdk.client.subscriptions.OpcUaMonitoredItem;
import org.eclipse.milo.opcua.sdk.client.subscriptions.OpcUaSubscription;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned;
import org.eclipse.milo.opcua.stack.core.types.enumerated.DataChangeTrigger;
import org.eclipse.milo.opcua.stack.core.types.enumerated.DeadbandType;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MonitoringMode;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.DataChangeFilter;
import org.eclipse.milo.opcua.stack.core.types.structured.MonitoringFilter;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * OPC UA 抽象基类。
 */
@Slf4j
public abstract class AbstractOpcUaCollector extends ConnectionBackedCollector {

    private ConnectionAdapter<OpcUaClient> connectionAdapter;
    protected OpcUaClient client;
    protected String endpointUrl;
    protected String securityPolicy;
    protected String username;
    protected String password;
    protected int requestTimeout = 5000;
    protected double subscriptionInterval = 1000;

    protected final Map<String, OpcUaSubscription> subscriptions = new ConcurrentHashMap<>();

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doConnect() throws Exception {
        log.info("开始建立 OPC UA 连接: {}", deviceInfo.getDeviceId());

        DeviceConnection connection = requireConnectionConfig();
        initOpcUaConfig(deviceInfo, connection);

        OpcUaConnectionAdapter opcUaAdapter = createAndConnectAdapter(
                connection,
                OpcUaConnectionAdapter.class,
                "OPC UA");

        this.connectionAdapter = opcUaAdapter;
        this.client = opcUaAdapter.getClient();

        log.info("OPC UA连接建立成功: end点位={} securityPolicy={}", endpointUrl, securityPolicy);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doDisconnect() {
        removeManagedConnection("OPC UA");
        connectionAdapter = null;
        client = null;
        subscriptions.clear();
    }

    /**
     * 处理组件生命周期。
     */
    protected void initOpcUaConfig(DeviceInfo deviceInfo, DeviceConnection connection) {
        endpointUrl = connection.getUrl();
        securityPolicy = connection.getSecurityPolicy();
        username = connection.getUsername();
        password = connection.getPassword();
        requestTimeout = connection.getReadTimeout() != null ? connection.getReadTimeout() : 0;
        subscriptionInterval = connection.getSubscriptionInterval();
    }

    /**
     * 查询并返回业务数据。
     */
    protected Object readValue(OpcUaAddress address) throws Exception {
        NodeId nodeId = address.toNodeId();
        DataValue value = readValueWithAliasFallback(nodeId);
        return value != null && value.getValue() != null ? value.getValue().getValue() : null;
    }

    /**
     * 批量读取并对 ProtoForge 展示的短字符串 NodeId 执行设备前缀回退。
     */
    protected List<DataValue> readValuesWithAliasFallback(List<NodeId> nodeIds) throws Exception {
        List<DataValue> values = client.readValues(0, TimestampsToReturn.Both, nodeIds);
        List<DataValue> resolved = new ArrayList<>(values);
        for (int index = 0; index < nodeIds.size(); index++) {
            DataValue current = values.get(index);
            if (isUsable(current)) {
                continue;
            }
            NodeId alias = resolveDeviceScopedAlias(nodeIds.get(index));
            if (alias == null || alias.equals(nodeIds.get(index))) {
                continue;
            }
            try {
                DataValue fallback = client.readValue(0, TimestampsToReturn.Both, alias);
                if (isUsable(fallback)) {
                    resolved.set(index, fallback);
                    log.debug("OPC UA 已使用设备作用域 NodeId 回退: 原始={}, 实际={}",
                            nodeIds.get(index), alias);
                }
            } catch (Exception exception) {
                log.debug("OPC UA NodeId 回退读取失败: {}", alias, exception);
            }
        }
        return resolved;
    }

    private DataValue readValueWithAliasFallback(NodeId nodeId) throws Exception {
        DataValue value = client.readValue(0, TimestampsToReturn.Both, nodeId);
        if (isUsable(value)) {
            return value;
        }
        NodeId alias = resolveDeviceScopedAlias(nodeId);
        if (alias == null || alias.equals(nodeId)) {
            return value;
        }
        try {
            DataValue fallback = client.readValue(0, TimestampsToReturn.Both, alias);
            return isUsable(fallback) ? fallback : value;
        } catch (Exception exception) {
            log.debug("OPC UA NodeId 回退读取失败: {}", alias, exception);
            return value;
        }
    }

    private boolean isUsable(DataValue value) {
        return value != null
                && value.getStatusCode() != null
                && value.getStatusCode().isGood()
                && value.getValue() != null
                && value.getValue().getValue() != null;
    }

    private NodeId resolveDeviceScopedAlias(NodeId nodeId) {
        if (nodeId == null || !(nodeId.getIdentifier() instanceof String identifier)
                || identifier.isBlank() || identifier.contains(".")) {
            return null;
        }
        String prefix = resolveDeviceNodePrefix();
        if (prefix == null || prefix.isBlank()) {
            return null;
        }
        return new NodeId(nodeId.getNamespaceIndex(), prefix + "." + identifier);
    }

    private String resolveDeviceNodePrefix() {
        if (deviceInfo == null || deviceInfo.getDeviceId() == null) {
            return null;
        }
        String deviceId = deviceInfo.getDeviceId();
        int protocolSeparator = deviceId.indexOf('_', 3);
        return protocolSeparator > 0 && protocolSeparator + 1 < deviceId.length()
                ? deviceId.substring(protocolSeparator + 1)
                : null;
    }

    /**
     * 查询并返回业务数据。
     */
    protected Map<String, Object> readValues(List<DataPoint> points) throws Exception {
        List<NodeId> nodeIds = new ArrayList<>();
        for (DataPoint point : points) {
            OpcUaAddress address = OpcUaAddressParser.parse(point);
            nodeIds.add(address.toNodeId());
        }
        List<DataValue> values = readValuesWithAliasFallback(nodeIds);
        Map<String, Object> result = new HashMap<>();
        for (int i = 0; i < points.size(); i++) {
            DataValue value = values.get(i);
            result.put(points.get(i).getPointId(),
                    value != null ? value.getValue().getValue() : null);
        }
        return result;
    }

    /**
     * 写入或持久化业务数据。
     */
    protected boolean writeValue(OpcUaAddress address, Object rawValue) throws Exception {
        Variant variant = OpcUaAddressParser.toVariant(rawValue, address.getDataType());
        List<StatusCode> results = client.writeValues(
                List.of(address.toNodeId()),
                List.of(DataValue.valueOnly(variant))
        );
        return !results.isEmpty() && results.get(0).isGood();
    }

    /**
     * 创建并返回业务对象。
     */
    protected OpcUaSubscription createSubscription() throws Exception {
        OpcUaSubscription subscription = new OpcUaSubscription(client, subscriptionInterval);
        subscription.create();
        String key = subscription.getSubscriptionId()
                .map(UInteger::toString)
                .orElse(UUID.randomUUID().toString());
        subscriptions.put(key, subscription);
        return subscription;
    }

    /**
     * 执行当前业务逻辑。
     */
    protected OpcUaMonitoredItem addMonitoredItem(OpcUaSubscription subscription,
                                                   OpcUaAddress address,
                                                   Consumer<OpcUaMonitoredItem> configurator) throws Exception {
        OpcUaMonitoredItem item = OpcUaMonitoredItem.newDataItem(address.toNodeId(), MonitoringMode.Reporting);

        Double publishingInterval = subscription.getPublishingInterval();
        double sampling = address.getSamplingInterval() > 0
                ? address.getSamplingInterval()
                : (publishingInterval != null ? publishingInterval : subscriptionInterval);
        item.setSamplingInterval(sampling);
        item.setQueueSize(Unsigned.uint(Math.max(1, address.getQueueSize())));
        MonitoringFilter filter = buildFilter(address);
        if (filter != null) {
            item.setFilter(filter);
        }
        if (configurator != null) {
            configurator.accept(item);
        }

        subscription.addMonitoredItem(item);
        List<MonitoredItemServiceOperationResult> results = subscription.createMonitoredItems(candidate -> candidate == item);
        MonitoredItemServiceOperationResult result = results.isEmpty() ? null : results.get(0);
        if (result == null || !result.isGood()) {
            throw new IllegalStateException("Failed to create OPC UA monitored item: "
                    + (result != null ? result.serviceResult() : "unknown"));
        }
        return item;
    }

    /**
     * 创建并返回业务对象。
     */
    private MonitoringFilter buildFilter(OpcUaAddress address) {
        if (address.getDeadband() <= 0) {
            return null;
        }
        return new DataChangeFilter(
                DataChangeTrigger.StatusValue,
                Unsigned.uint(DeadbandType.Absolute.getValue()),
                address.getDeadband()
        );
    }

}
