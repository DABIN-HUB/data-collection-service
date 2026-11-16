package com.wangbin.collector.core.connection.adapter;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.eclipse.milo.opcua.sdk.server.EndpointConfig;
import org.eclipse.milo.opcua.sdk.client.DiscoveryClient;
import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.OpcUaServerConfig;
import org.eclipse.milo.opcua.sdk.server.identity.X509IdentityValidator;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.security.CertificateStore;
import org.eclipse.milo.opcua.stack.core.security.DefaultApplicationGroup;
import org.eclipse.milo.opcua.stack.core.security.DefaultCertificateManager;
import org.eclipse.milo.opcua.stack.core.security.DefaultServerCertificateValidator;
import org.eclipse.milo.opcua.stack.core.security.MemoryCertificateQuarantine;
import org.eclipse.milo.opcua.stack.core.security.MemoryCertificateStore;
import org.eclipse.milo.opcua.stack.core.security.MemoryTrustListManager;
import org.eclipse.milo.opcua.stack.core.security.RsaSha256CertificateFactory;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.transport.TransportProfile;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.BuildInfo;
import org.eclipse.milo.opcua.stack.transport.server.OpcServerTransportFactory;
import org.eclipse.milo.opcua.stack.transport.server.tcp.OpcTcpServerTransport;
import org.eclipse.milo.opcua.stack.transport.server.tcp.OpcTcpServerTransportConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertPathBuilderException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 在回环端点实测 Milo 安全会话、命名空间、浏览与读取；证书仅在临时目录生成。
 */
class OpcUaSecureLoopbackTest {
    private static final String SERVER_URI = "urn:wangbin:isolated:opcua:server";
    private static final String CLIENT_URI = "urn:wangbin:isolated:opcua:client";

    @TempDir
    Path temporaryDirectory;

    @Test
    void secureSessionMustReadAndRejectUntrustedServerWithoutFallback() throws Exception {
        KeyPair caKey = newKeyPair();
        X509Certificate ca = issue(caKey, null, null, true, null, SERVER_URI, true);
        KeyPair serverKey = newKeyPair();
        X509Certificate serverCertificate = issue(serverKey, ca, caKey, false, "127.0.0.1", SERVER_URI, true);
        KeyPair clientKey = newKeyPair();
        X509Certificate clientCertificate = issue(clientKey, ca, caKey, false, "127.0.0.1", CLIENT_URI, false);
        Path root = temporaryDirectory.resolve("pki");
        Path trusted = root.resolve("secure-client/trusted/certs");
        Files.createDirectories(trusted);
        Path certificatePath = trusted.resolve("approved-ca.der");
        Files.write(certificatePath, ca.getEncoded());
        Path clientFile = temporaryDirectory.resolve("client.p12");
        char[] password = UUID.randomUUID().toString().toCharArray();
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, password);
        store.setKeyEntry("client", clientKey.getPrivate(), password, new Certificate[]{clientCertificate, ca});
        try (OutputStream output = Files.newOutputStream(clientFile)) {
            store.store(output, password);
        }

        int port = freePort();
        OpcUaServer server = newServer(port, ca, serverKey, serverCertificate, ca, clientCertificate);
        OpcUaConnectionAdapter adapter = newAdapter(root, clientFile, password, port);
        try {
            server.startup().get(10, TimeUnit.SECONDS);
            var discovered = DiscoveryClient.getEndpoints("opc.tcp://127.0.0.1:" + port + "/secured")
                    .get(10, TimeUnit.SECONDS);
            assertEquals(1, discovered.size());
            assertEquals(SecurityPolicy.Basic256Sha256.getUri(), discovered.get(0).getSecurityPolicyUri());
            assertEquals(MessageSecurityMode.SignAndEncrypt, discovered.get(0).getSecurityMode());
            adapter.connect();
            assertTrue(adapter.isConnected());
            assertNotNull(adapter.getClient());
            List<?> browsed = adapter.getClient().getAddressSpace().browse(Identifiers.ObjectsFolder);
            assertTrue(!browsed.isEmpty());
            DataValue namespaces = adapter.getClient().readValue(0, TimestampsToReturn.Both,
                    Identifiers.Server_NamespaceArray);
            assertTrue(namespaces.getStatusCode().isGood());
            assertNotNull(namespaces.getValue().getValue());
            DataValue read = adapter.getClient().readValue(0, TimestampsToReturn.Both,
                    Identifiers.Server_ServerStatus_CurrentTime);
            assertTrue(read.getStatusCode().isGood());
            assertNotNull(read.getValue().getValue());
            var subscription = adapter.getClient().createSubscription(1000.0,
                    Unsigned.uint(100), Unsigned.uint(3), Unsigned.uint(0), true, Unsigned.ubyte(0));
            assertTrue(subscription.getSubscriptionId().longValue() > 0);
            var deletion = adapter.getClient().deleteSubscriptions(List.of(subscription.getSubscriptionId()));
            assertTrue(deletion.getResults()[0].isGood());
            adapter.disconnect();
            assertNull(adapter.getClient());
            adapter.connect();
            assertTrue(adapter.isConnected());
            adapter.closeResources();
            adapter.closeResources();
            assertNull(adapter.getClient());

            KeyPair foreignCaKey = newKeyPair();
            X509Certificate foreignCa = issue(foreignCaKey, null, null, true, null, SERVER_URI, true);
            KeyPair foreignServerKey = newKeyPair();
            X509Certificate foreignServerCertificate = issue(foreignServerKey, foreignCa, foreignCaKey,
                    false, "127.0.0.1", SERVER_URI, true);
            int foreignPort = freePort();
            OpcUaServer foreignServer = newServer(foreignPort, foreignCa, foreignServerKey,
                    foreignServerCertificate, ca, clientCertificate);
            OpcUaConnectionAdapter foreignAdapter = newAdapter(root, clientFile, password, foreignPort);
            try {
                foreignServer.startup().get(10, TimeUnit.SECONDS);
                Exception rejected = assertThrows(Exception.class, foreignAdapter::connect);
                assertTrue(hasUntrustedCertificateCause(rejected));
                assertNull(foreignAdapter.getClient());
                try (var entries = Files.list(trusted)) {
                    assertEquals(1, entries.count());
                }
            } finally {
                try {
                    foreignAdapter.closeResources();
                } finally {
                    foreignServer.shutdown().get(10, TimeUnit.SECONDS);
                }
            }
            adapter.closeResources();
            Files.delete(certificatePath);
            assertThrows(Exception.class, adapter::connect);
            assertNull(adapter.getClient());
        } finally {
            try {
                adapter.closeResources();
            } finally {
                server.shutdown().get(10, TimeUnit.SECONDS);
            }
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static boolean hasUntrustedCertificateCause(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof CertPathBuilderException) {
                return true;
            }
        }
        return false;
    }

    private static OpcUaServer newServer(int port, X509Certificate ca, KeyPair serverKey,
                                         X509Certificate serverCertificate, X509Certificate clientCa,
                                         X509Certificate clientCertificate) throws Exception {
        MemoryTrustListManager serverTrust = new MemoryTrustListManager();
        serverTrust.addTrustedCertificate(clientCa);
        MemoryCertificateQuarantine quarantine = new MemoryCertificateQuarantine();
        MemoryCertificateStore certStore = new MemoryCertificateStore();
        certStore.set(NodeIds.RsaSha256ApplicationCertificateType,
                new CertificateStore.Entry(serverKey.getPrivate(), new X509Certificate[]{serverCertificate, ca}));
        RsaSha256CertificateFactory factory = new RsaSha256CertificateFactory() {
            @Override
            protected X509Certificate[] createRsaSha256CertificateChain(KeyPair pair) {
                return new X509Certificate[]{serverCertificate, ca};
            }
        };
        DefaultApplicationGroup group = DefaultApplicationGroup.createAndInitialize(serverTrust, certStore,
                factory, new DefaultServerCertificateValidator(serverTrust, quarantine));
        DefaultCertificateManager certificateManager = new DefaultCertificateManager(quarantine, group);
        EndpointConfig endpoint = EndpointConfig.newBuilder()
                .setTransportProfile(TransportProfile.TCP_UASC_UABINARY)
                .setBindAddress("127.0.0.1")
                .setHostname("127.0.0.1")
                .setBindPort(port)
                .setPath("/secured")
                .setCertificate(serverCertificate)
                .setSecurityPolicy(SecurityPolicy.Basic256Sha256)
                .setSecurityMode(MessageSecurityMode.SignAndEncrypt)
                .addTokenPolicies(OpcUaServerConfig.USER_TOKEN_POLICY_X509)
                .build();
        OpcUaServerConfig serverConfig = OpcUaServerConfig.builder()
                .setApplicationName(LocalizedText.english("isolated-opcua-server"))
                .setApplicationUri(SERVER_URI)
                .setProductUri(SERVER_URI)
                .setBuildInfo(new BuildInfo(SERVER_URI, "wangbin", "isolated-opcua-server", "1", "", DateTime.now()))
                .setEndpoints(Set.of(endpoint))
                .setCertificateManager(certificateManager)
                .setIdentityValidator(new X509IdentityValidator(clientCertificate::equals))
                .build();
        OpcServerTransportFactory transport = profile -> new OpcTcpServerTransport(
                OpcTcpServerTransportConfig.newBuilder().build());
        return new OpcUaServer(serverConfig, transport);
    }

    private static OpcUaConnectionAdapter newAdapter(Path root, Path clientFile, char[] password, int port) {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("secure-client");
        DeviceConnection connection = new DeviceConnection();
        connection.setConnectionId("secure-client");
        connection.setDeviceId("secure-client");
        connection.setUrl("opc.tcp://127.0.0.1:" + port + "/secured");
        connection.setExtJson(Map.of("securityPolicy", "Basic256Sha256", "securityMode", "SignAndEncrypt",
                "authType", "CERT", "clientCertPath", clientFile.toString(),
                "clientCertPassword", new String(password), "pkiBaseDir", root.toString(),
                "requestTimeoutMs", 5000, "connectTimeoutMs", 5000));
        return new OpcUaConnectionAdapter(device, connection);
    }

    private static KeyPair newKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static X509Certificate issue(KeyPair subject, X509Certificate issuer, KeyPair issuerKey, boolean ca,
                                          String ip, String uri, boolean server) throws Exception {
        Instant now = Instant.now();
        X500Name name = new X500Name("CN=isolated-" + (ca ? "ca" : server ? "server" : "client"));
        X500Name issuerName = issuer == null ? name : new X500Name(issuer.getSubjectX500Principal().getName());
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(issuerName,
                java.math.BigInteger.valueOf(now.toEpochMilli() + (ca ? 1 : server ? 2 : 3)),
                Date.from(now.minusSeconds(3600)), Date.from(now.plusSeconds(86400)), name, subject.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(ca ? KeyUsage.keyCertSign | KeyUsage.cRLSign
                : KeyUsage.digitalSignature | KeyUsage.nonRepudiation | KeyUsage.keyEncipherment | KeyUsage.dataEncipherment));
        if (!ca) {
            builder.addExtension(Extension.extendedKeyUsage, false,
                    new ExtendedKeyUsage(server ? KeyPurposeId.id_kp_serverAuth : KeyPurposeId.id_kp_clientAuth));
            builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName[]{
                    new GeneralName(GeneralName.iPAddress, ip),
                    new GeneralName(GeneralName.uniformResourceIdentifier, uri)
            }));
        }
        return new JcaX509CertificateConverter().getCertificate(builder.build(
                new JcaContentSignerBuilder("SHA256withRSA").build(
                        issuerKey == null ? subject.getPrivate() : issuerKey.getPrivate())));
    }
}
