package com.wangbin.collector.core.collector.protocol.opc.ua.address;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.core.collector.protocol.opc.ua.util.OpcUaAddressParser;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;

/**
 * OPC UA 有效 NodeId 解析器，集中处理点位配置和显式别名规则。
 */
public class OpcUaNodeIdResolver {
    public NodeId resolve(DataPoint point, DeviceConnection connection) {
        return resolve(OpcUaAddressParser.parse(point).toNodeId(), connection);
    }

    public NodeId resolve(NodeId nodeId, DeviceConnection connection) {
        if (nodeId == null || connection == null) {
            return nodeId;
        }
        String mode = connection.getString("nodeIdAliasMode", "NONE");
        if (!"PREFIX".equalsIgnoreCase(mode) || !(nodeId.getIdentifier() instanceof String identifier)
                || identifier.isBlank() || identifier.contains(".")) {
            return nodeId;
        }
        String prefix = connection.getString("nodeIdPrefix", "");
        if (prefix.isBlank()) {
            return nodeId;
        }
        return new NodeId(nodeId.getNamespaceIndex(), prefix + "." + identifier);
    }
}
