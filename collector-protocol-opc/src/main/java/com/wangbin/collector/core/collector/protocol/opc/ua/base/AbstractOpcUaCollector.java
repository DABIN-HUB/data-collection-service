package com.wangbin.collector.core.collector.protocol.opc.ua.base;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.protocol.base.ConnectionBackedCollector;
import com.wangbin.collector.core.collector.protocol.opc.ua.address.OpcUaNodeIdResolver;
import com.wangbin.collector.core.collector.protocol.opc.ua.domain.OpcUaAddress;
import com.wangbin.collector.core.collector.protocol.opc.ua.domain.OpcUaDataType;
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
    private final OpcUaNodeIdResolver nodeIdResolver = new OpcUaNodeIdResolver();

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
    protected Object readValue(OpcUaAddress address, NodeId nodeId) throws Exception {
        DataValue value = client.readValue(0, TimestampsToReturn.Both, nodeId);
        if (!isGoodRead(value)) {
            throw new IllegalStateException("OPC UA 读取失败: nodeId=" + nodeId + ", status=" + readStatus(value));
        }
        return value.getValue() != null ? value.getValue().getValue() : null;
    }

    protected boolean isGoodRead(DataValue value) {
        return value != null && value.getStatusCode() != null && value.getStatusCode().isGood();
    }

    protected String readStatus(DataValue value) {
        return value != null && value.getStatusCode() != null ? value.getStatusCode().toString() : "null";
    }

    protected NodeId resolveNodeId(DataPoint point) {
        DeviceConnection connection = requireConnectionConfig();
        NodeId resolved = nodeIdResolver.resolve(point, connection);
        log.debug("OPC UA NodeId 解析：设备={}，点位={}，pointCode={}，address={}，additionalConfig.nodeId={}，additionalConfig.id={}，aliasMode={}，prefix={}，resolved={}",
                deviceInfo != null ? deviceInfo.getDeviceId() : null,
                point != null ? point.getPointId() : null,
                point != null ? point.getPointCode() : null,
                point != null ? point.getAddress() : null,
                point != null ? point.getAdditionalConfig("nodeId") : null,
                point != null ? point.getAdditionalConfig("id") : null,
                connection.getString("nodeIdAliasMode", "NONE"),
                connection.getString("nodeIdPrefix", ""), resolved);
        return resolved;
    }

    protected NodeId resolveNodeIdForCommand(NodeId nodeId) {
        return nodeIdResolver.resolve(nodeId, requireConnectionConfig());
    }

    protected List<DataValue> readValues(List<NodeId> nodeIds) throws Exception {
        return client.readValues(0, TimestampsToReturn.Both, nodeIds);
    }

    protected Map<String, Object> readValues(List<DataPoint> points, List<NodeId> nodeIds) throws Exception {
        List<DataValue> values = readValues(nodeIds);
        if (values == null || values.size() != points.size()) {
            throw new IllegalStateException("OPC UA 批量响应数量不匹配: requested=" + points.size()
                    + ", received=" + (values == null ? "null" : values.size()));
        }
        Map<String, Object> result = new HashMap<>();
        String firstFailure = null;
        for (int i = 0; i < points.size(); i++) {
            DataValue value = values.get(i);
            if (!isGoodRead(value)) {
                String failure = "pointId=" + points.get(i).getPointId() + ", nodeId=" + nodeIds.get(i)
                        + ", status=" + readStatus(value);
                if (firstFailure == null) {
                    firstFailure = failure;
                }
                log.warn("OPC UA 批量读取点位失败: {}", failure);
                continue;
            }
            result.put(points.get(i).getPointId(), value.getValue() != null
                    ? value.getValue().getValue() : null);
        }
        if (result.isEmpty() && firstFailure != null) {
            throw new IllegalStateException("OPC UA 批量读取全部失败: " + firstFailure);
        }
        return result;
    }

    protected boolean writeValue(NodeId nodeId, OpcUaDataType dataType, Object rawValue) throws Exception {
        Variant variant = OpcUaAddressParser.toVariant(rawValue, dataType);
        List<StatusCode> results = client.writeValues(List.of(nodeId), List.of(DataValue.valueOnly(variant)));
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
                                                   NodeId nodeId,
                                                   Consumer<OpcUaMonitoredItem> configurator) throws Exception {
        OpcUaMonitoredItem item = OpcUaMonitoredItem.newDataItem(nodeId, MonitoringMode.Reporting);

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
