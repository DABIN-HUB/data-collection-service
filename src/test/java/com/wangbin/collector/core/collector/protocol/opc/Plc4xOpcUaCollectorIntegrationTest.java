package com.wangbin.collector.core.collector.protocol.opc;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.collector.protocol.opc.plc4x.util.Plc4xOpcUaAddressParser;
import com.wangbin.collector.core.connection.adapter.Plc4xOpcUaConnectionAdapter;
import com.wangbin.collector.core.processor.DataQualityProcessor;
import org.apache.plc4x.java.api.PlcConnection;
import org.apache.plc4x.java.api.messages.PlcReadResponse;
import org.apache.plc4x.java.api.types.PlcResponseCode;
import org.eclipse.milo.opcua.sdk.core.AccessLevel;
import org.eclipse.milo.opcua.sdk.server.EndpointConfig;
import org.eclipse.milo.opcua.sdk.server.ManagedNamespaceWithLifecycle;
import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.OpcUaServerConfig;
import org.eclipse.milo.opcua.sdk.server.identity.AnonymousIdentityValidator;
import org.eclipse.milo.opcua.sdk.server.identity.CompositeValidator;
import org.eclipse.milo.opcua.sdk.server.identity.UsernameIdentityValidator;
import org.eclipse.milo.opcua.sdk.server.items.DataItem;
import org.eclipse.milo.opcua.sdk.server.items.MonitoredItem;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaObjectNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.sdk.server.util.SubscriptionModel;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.transport.TransportProfile;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.eclipse.milo.opcua.stack.core.types.structured.BuildInfo;
import org.eclipse.milo.opcua.stack.transport.server.OpcServerTransportFactory;
import org.eclipse.milo.opcua.stack.transport.server.tcp.OpcTcpServerTransport;
import org.eclipse.milo.opcua.stack.transport.server.tcp.OpcTcpServerTransportConfig;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Plc4xOpcUaCollectorIntegrationTest {

    @Test
    void shouldReadWriteExposeRuntimeBrowseBoundaryAndRegisterSubscriptionAgainstEmbeddedOpcUaServer() throws Exception {
        try (EmbeddedOpcUaTestServer server = EmbeddedOpcUaTestServer.start()) {
            Plc4xOpcUaConnectionAdapter connectionAdapter = new Plc4xOpcUaConnectionAdapter(
                    device("dev-opcua-plc4x-it"),
                    anonymousConnection(server));
            connectionAdapter.connect();

            Plc4xOpcUaCollector collector = prepareConnectedCollector(device("dev-opcua-plc4x-it"), connectionAdapter);
            DataPoint temperature = point("temperature-point", "temperature", server.temperatureNodeId(), "FLOAT");

            assertEquals(12.5d, ((Number) collector.readPoint(temperature)).doubleValue(), 0.0001d);

            assertTrue(collector.writePoint(temperature, 25.5d));
            assertEquals(25.5d, server.getTemperature(), 0.0001d);

            Map<String, Object> status = collector.getDeviceStatus();
            assertEquals(false, status.get("browseable"));
            assertEquals(true, status.get("subscribable"));
            assertThrows(CollectorException.class, () -> collector.executeCommand("browse", Map.of()));

            collector.subscribe(List.of(temperature));
            Map<String, Object> subscribedStatus = collector.getDeviceStatus();
            assertEquals(1, subscribedStatus.get("activeSubscriptions"));

            collector.unsubscribe(List.of(temperature));
            Map<String, Object> unsubscribedStatus = collector.getDeviceStatus();
            assertEquals(0, unsubscribedStatus.get("activeSubscriptions"));
            connectionAdapter.disconnect();
        }
    }

    @Test
    void shouldConnectWithCompatibilityAliasesAgainstEmbeddedOpcUaServer() throws Exception {
        try (EmbeddedOpcUaTestServer server = EmbeddedOpcUaTestServer.start()) {
            Plc4xOpcUaConnectionAdapter connectionAdapter = new Plc4xOpcUaConnectionAdapter(
                    device("dev-opcua-plc4x-alias-it"),
                    compatibilityAliasConnection(server));
            connectionAdapter.connect();

            assertTrue(connectionAdapter.isConnected());
            assertTrue(connectionAdapter.healthCheck());
            assertTrue(connectionAdapter.getConnectionString().contains("security-policy=NONE"));
            assertTrue(connectionAdapter.getConnectionString().contains("message-security=NONE"));

            PlcConnection connection = connectionAdapter.getClient();
            String address = Plc4xOpcUaAddressParser.parse(server.temperatureNodeId(), "FLOAT").getPlc4xAddress();
            PlcReadResponse response = connection.readRequestBuilder()
                    .addTagAddress("temperature", address)
                    .build()
                    .execute()
                    .get(5, TimeUnit.SECONDS);

            assertEquals(PlcResponseCode.OK, response.getResponseCode("temperature"));
            assertEquals(12.5d, response.getPlcValue("temperature").getFloat(), 0.0001d);

            connectionAdapter.disconnect();
        }
    }

    private Plc4xOpcUaCollector prepareConnectedCollector(DeviceInfo deviceInfo,
                                                          Plc4xOpcUaConnectionAdapter connectionAdapter) throws Exception {
        Plc4xOpcUaCollector collector = new Plc4xOpcUaCollector();
        collector.init(deviceInfo);
        ReflectionTestUtils.setField(collector, "dataQualityProcessor", new DataQualityProcessor(null));
        ReflectionTestUtils.setField(collector, "connected", true);
        ReflectionTestUtils.setField(collector, "connectionStatus", "CONNECTED");
        ReflectionTestUtils.setField(collector, "connectionAdapter", connectionAdapter);
        return collector;
    }

    private DeviceInfo device(String deviceId) {
        DeviceInfo deviceInfo = new DeviceInfo();
        deviceInfo.setDeviceId(deviceId);
        deviceInfo.setDeviceName(deviceId);
        deviceInfo.setProtocolType("OPC_UA_PLC4X");
        deviceInfo.setConnectionType("TCP");
        deviceInfo.setCollectionInterval(1000);
        return deviceInfo;
    }

    private DeviceConnection anonymousConnection(EmbeddedOpcUaTestServer server) {
        DeviceConnection connection = new DeviceConnection();
        connection.setConnectionType("OPC_UA_PLC4X");
        connection.setHost("127.0.0.1");
        connection.setPort(server.port());

        Map<String, Object> extJson = new LinkedHashMap<>();
        extJson.put("endpointUrl", server.endpointUrl());
        extJson.put("authType", "ANONYMOUS");
        extJson.put("discovery", false);
        extJson.put("securityPolicy", "NONE");
        extJson.put("messageSecurity", "NONE");
        extJson.put("requestTimeoutMs", 5000);
        extJson.put("connectTimeoutMs", 5000);
        connection.setExtJson(extJson);
        return connection;
    }

    private DeviceConnection compatibilityAliasConnection(EmbeddedOpcUaTestServer server) {
        DeviceConnection connection = new DeviceConnection();
        connection.setConnectionType("OPC_UA_PLC4X");
        connection.setHost("127.0.0.1");
        connection.setPort(server.port());

        Map<String, Object> extJson = new LinkedHashMap<>();
        extJson.put("endpointUrl", server.endpointUrl());
        extJson.put("authType", "ANONYMOUS");
        extJson.put("discovery", false);
        extJson.put("securityPolicy", "http://opcfoundation.org/UA/SecurityPolicy#None");
        extJson.put("securityMode", "NONE");
        extJson.put("requestTimeoutMs", 5000);
        extJson.put("connectTimeoutMs", 5000);
        connection.setExtJson(extJson);
        return connection;
    }

    private DataPoint point(String pointId, String pointCode, String address, String dataType) {
        DataPoint point = new DataPoint();
        point.setPointId(pointId);
        point.setPointCode(pointCode);
        point.setPointName(pointCode);
        point.setDeviceId("dev-opcua-plc4x-it");
        point.setAddress(address);
        point.setDataType(dataType);
        point.setReadWrite("RW");
        point.setStatus(1);
        point.setCollectionMode("SUBSCRIPTION");
        return point;
    }

    private static final class EmbeddedOpcUaTestServer implements AutoCloseable {

        private static final String NAMESPACE_URI = "urn:wangbin:data-collection-service:test:opcua:plc4x";
        // Keep the local embedded endpoint path conservative for the current PLC4X OPC UA parser.
        private static final String ENDPOINT_PATH = "/plc4x_opcua_test";

        private final int port;
        private final OpcUaServer server;
        private final TestNamespace namespace;

        private EmbeddedOpcUaTestServer() throws Exception {
            this.port = randomAvailablePort();

            EndpointConfig endpointConfig = EndpointConfig.newBuilder()
                    .setTransportProfile(TransportProfile.TCP_UASC_UABINARY)
                    .setBindAddress("127.0.0.1")
                    .setBindPort(port)
                    .setHostname("127.0.0.1")
                    .setPath(ENDPOINT_PATH)
                    .setSecurityPolicy(SecurityPolicy.None)
                    .setSecurityMode(MessageSecurityMode.None)
                    .addTokenPolicies(
                            OpcUaServerConfig.USER_TOKEN_POLICY_ANONYMOUS,
                            OpcUaServerConfig.USER_TOKEN_POLICY_USERNAME)
                    .build();

            OpcUaServerConfig serverConfig = OpcUaServerConfig.builder()
                    .setApplicationName(LocalizedText.english("PLC4X OPC UA Test Server"))
                    .setApplicationUri("urn:wangbin:data-collection-service:test:opcua:plc4x:server")
                    .setProductUri("urn:wangbin:data-collection-service:test:opcua:plc4x")
                    .setBuildInfo(new BuildInfo(
                            "wangbin",
                            "urn:wangbin:data-collection-service:test:opcua:plc4x",
                            "PLC4X OPC UA Test Server",
                            "1.0.0",
                            "test",
                            DateTime.now()))
                    .setEndpoints(Set.of(endpointConfig))
                    .setIdentityValidator(new CompositeValidator(
                            AnonymousIdentityValidator.INSTANCE,
                            new UsernameIdentityValidator(challenge ->
                                    "plc4x-user".equals(challenge.getUsername())
                                            && "plc4x-secret".equals(challenge.getPassword()))))
                    .build();

            OpcServerTransportFactory transportFactory = transportProfile -> {
                if (transportProfile != TransportProfile.TCP_UASC_UABINARY) {
                    throw new IllegalArgumentException("Unsupported transport profile: " + transportProfile);
                }
                return new OpcTcpServerTransport(OpcTcpServerTransportConfig.newBuilder().build());
            };

            this.server = new OpcUaServer(serverConfig, transportFactory);
            this.namespace = new TestNamespace(server);
            this.namespace.startup();
            this.server.startup().get(10, TimeUnit.SECONDS);
        }

        static EmbeddedOpcUaTestServer start() throws Exception {
            return new EmbeddedOpcUaTestServer();
        }

        int port() {
            return port;
        }

        String endpointUrl() {
            return "opc.tcp://127.0.0.1:" + port + ENDPOINT_PATH;
        }

        String temperatureNodeId() {
            return "ns=2;s=Channel1.Device1.Tag1";
        }

        float getTemperature() {
            return namespace.getTemperature();
        }

        @Override
        public void close() throws Exception {
            try {
                namespace.shutdown();
            } finally {
                server.shutdown().get(10, TimeUnit.SECONDS);
            }
        }

        private static int randomAvailablePort() throws IOException {
            try (ServerSocket socket = new ServerSocket(0)) {
                socket.setReuseAddress(true);
                return socket.getLocalPort();
            }
        }
    }

    private static final class TestNamespace extends ManagedNamespaceWithLifecycle {

        private final SubscriptionModel subscriptionModel;
        private UaVariableNode temperatureNode;

        private TestNamespace(OpcUaServer server) {
            super(server, EmbeddedOpcUaTestServer.NAMESPACE_URI);
            this.subscriptionModel = new SubscriptionModel(server, this);
            getLifecycleManager().addLifecycle(subscriptionModel);
            registerNodeManager(getNodeManager());
            registerAddressSpace(this);
            addNodes();
        }

        @Override
        public void onDataItemsCreated(List<DataItem> dataItems) {
            subscriptionModel.onDataItemsCreated(dataItems);
        }

        @Override
        public void onDataItemsModified(List<DataItem> dataItems) {
            subscriptionModel.onDataItemsModified(dataItems);
        }

        @Override
        public void onDataItemsDeleted(List<DataItem> dataItems) {
            subscriptionModel.onDataItemsDeleted(dataItems);
        }

        @Override
        public void onMonitoringModeChanged(List<MonitoredItem> monitoredItems) {
            subscriptionModel.onMonitoringModeChanged(monitoredItems);
        }

        private float getTemperature() {
            return ((Number) temperatureNode.getValue().getValue().getValue()).floatValue();
        }

        private void addNodes() {
            UaFolderNode demoFolder = new UaFolderNode(
                    getNodeContext(),
                    newNodeId("PLC4X_DEMO"),
                    newQualifiedName("PLC4X_DEMO"),
                    LocalizedText.english("PLC4X_DEMO"));
            getNodeManager().addNode(demoFolder);

            UaVariableNode temperature = UaVariableNode.builder(getNodeContext())
                    .setNodeId(newNodeId("Channel1.Device1.Tag1"))
                    .setBrowseName(newQualifiedName("Temperature"))
                    .setDisplayName(LocalizedText.english("Temperature"))
                    .setDataType(Identifiers.Float)
                    .setTypeDefinition(Identifiers.BaseDataVariableType)
                    .setAccessLevel(AccessLevel.READ_WRITE)
                    .setUserAccessLevel(AccessLevel.READ_WRITE)
                    .setValue(new DataValue(new Variant(12.5f)))
                    .build();
            getNodeManager().addNode(temperature);
            demoFolder.addOrganizes(temperature);

            this.temperatureNode = temperature;

            UaObjectNode objectsFolder = (UaObjectNode) getServer()
                    .getAddressSpaceManager()
                    .getManagedNode(Identifiers.ObjectsFolder)
                    .orElseThrow();
            if (objectsFolder instanceof UaFolderNode folderNode) {
                folderNode.addOrganizes(demoFolder);
            } else {
                objectsFolder.addComponent(demoFolder);
            }
        }
    }
}
