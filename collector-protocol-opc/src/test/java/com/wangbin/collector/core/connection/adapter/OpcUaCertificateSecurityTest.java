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
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.security.CertificateValidator;
import org.eclipse.milo.opcua.sdk.client.identity.UsernameProvider;
import org.eclipse.milo.opcua.stack.core.security.FileBasedTrustListManager;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.builtin.ByteString;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.UserTokenPolicy;
import org.eclipse.milo.opcua.stack.core.types.enumerated.UserTokenType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用隔离目录和运行时生成的证书验证实际 Milo 1.0.8 信任及清理行为；不落盘私钥。
 */
class OpcUaCertificateSecurityTest {
    private static final String HOST = "opc-fixture.example.invalid";
    private static final String APPLICATION_URI = "urn:wangbin:opc-fixture:server";
    private static final AtomicLong SERIAL = new AtomicLong(1);

    @TempDir
    Path temporaryDirectory;

    @Test
    void trustedCaAndLeafAreAcceptedAfterInitializingTheCorrectDirectory() throws Exception {
        KeyPair caKey = keyPair();
        X509Certificate ca = certificate(caKey, null, null, true, null, APPLICATION_URI, 1);
        X509Certificate leaf = certificate(keyPair(), ca, caKey, false, HOST, APPLICATION_URI, 1);
        Path root = temporaryDirectory.resolve("pki");
        Path trusted = root.resolve("device-a/trusted/certs");
        Files.createDirectories(trusted);
        writeCertificate(trusted.resolve("ca.der"), ca);
        OpcUaConnectionAdapter adapter = adapter("device-a", root);
        try {
            CertificateValidator validator = validator(adapter);
            FileBasedTrustListManager manager = trustManager(adapter);
            assertEquals(List.of(ca), manager.getTrustedCertificates());
            assertTrue(manager.getIssuerCertificates().isEmpty());
            assertTrue(manager.getIssuerCrls().isEmpty());
            assertTrue(manager.getTrustedCrls().isEmpty());
            assertDoesNotThrow(() -> validator.validateCertificateChain(
                    List.of(leaf, ca), APPLICATION_URI, new String[]{HOST}));
            if (Files.getFileAttributeView(trusted, PosixFileAttributeView.class) != null) {
                assertEquals(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE), Files.getPosixFilePermissions(trusted));
                assertEquals(Files.getPosixFilePermissions(trusted), Files.getPosixFilePermissions(root.resolve("device-a")));
            }
        } finally {
            adapter.closeResources();
        }
    }

    @Test
    void invalidChainValidityHostnameAndApplicationUriAreRejectedWithoutTrustPollution() throws Exception {
        KeyPair caKey = keyPair();
        X509Certificate ca = certificate(caKey, null, null, true, null, APPLICATION_URI, 1);
        X509Certificate leaf = certificate(keyPair(), ca, caKey, false, HOST, APPLICATION_URI, 1);
        X509Certificate expired = certificate(keyPair(), ca, caKey, false, HOST, APPLICATION_URI, -1);
        KeyPair foreignKey = keyPair();
        X509Certificate foreignCa = certificate(foreignKey, null, null, true, null, APPLICATION_URI, 1);
        X509Certificate foreignLeaf = certificate(keyPair(), foreignCa, foreignKey, false, HOST, APPLICATION_URI, 1);
        X509Certificate forgedLeaf = certificate(keyPair(), ca, foreignKey, false, HOST, APPLICATION_URI, 1);
        Path root = temporaryDirectory.resolve("pki");
        Path trusted = root.resolve("device-a/trusted/certs");
        Files.createDirectories(trusted);
        writeCertificate(trusted.resolve("ca.der"), ca);
        OpcUaConnectionAdapter adapter = adapter("device-a", root);
        try {
            CertificateValidator validator = validator(adapter);
            assertThrows(UaException.class, () -> validator.validateCertificateChain(
                    List.of(foreignLeaf, foreignCa), APPLICATION_URI, new String[]{HOST}));
            assertThrows(UaException.class, () -> validator.validateCertificateChain(
                    List.of(forgedLeaf, ca), APPLICATION_URI, new String[]{HOST}));
            assertThrows(UaException.class, () -> validator.validateCertificateChain(
                    List.of(expired, ca), APPLICATION_URI, new String[]{HOST}));
            assertThrows(UaException.class, () -> validator.validateCertificateChain(
                    List.of(leaf, ca), APPLICATION_URI, new String[]{"other.example.invalid"}));
            assertThrows(UaException.class, () -> validator.validateCertificateChain(
                    List.of(leaf, ca), "urn:wangbin:other:server", new String[]{HOST}));
            assertEquals(List.of(ca), trustManager(adapter).getTrustedCertificates());
            try (var entries = Files.list(trusted)) {
                assertEquals(1, entries.count());
            }
        } finally {
            adapter.closeResources();
        }
    }

    @Test
    void twoDeviceTrustListsStayIsolatedAndAreClosedOnDisconnect() throws Exception {
        KeyPair firstKey = keyPair();
        KeyPair secondKey = keyPair();
        X509Certificate first = certificate(firstKey, null, null, true, null, APPLICATION_URI, 1);
        X509Certificate second = certificate(secondKey, null, null, true, null, APPLICATION_URI, 1);
        Path root = temporaryDirectory.resolve("pki");
        Files.createDirectories(root.resolve("device-a/trusted/certs"));
        Files.createDirectories(root.resolve("device-b/trusted/certs"));
        writeCertificate(root.resolve("device-a/trusted/certs/ca.der"), first);
        writeCertificate(root.resolve("device-b/trusted/certs/ca.der"), second);
        OpcUaConnectionAdapter a = adapter("device-a", root);
        OpcUaConnectionAdapter b = adapter("device-b", root);
        try {
            validator(a);
            validator(b);
            FileBasedTrustListManager managerA = trustManager(a);
            assertEquals(List.of(first), managerA.getTrustedCertificates());
            assertEquals(List.of(second), trustManager(b).getTrustedCertificates());
            a.closeResources();
            assertTrue(ReflectionTestUtils.getField(a, "trustListManager") == null);
            assertFalse(((Thread) ReflectionTestUtils.getField(managerA, "watchThread")).isAlive());
            assertEquals(List.of(second), trustManager(b).getTrustedCertificates());
        } finally {
            a.closeResources();
            b.closeResources();
        }
    }

    @Test
    void invalidPkiParentFailsClosedWithoutCreatingUnrelatedDirectories() throws Exception {
        Path notDirectory = temporaryDirectory.resolve("not-directory");
        Files.writeString(notDirectory, "占位", StandardCharsets.UTF_8);
        OpcUaConnectionAdapter adapter = adapter("device-a", notDirectory);
        assertThrows(Exception.class, () -> validator(adapter));
        assertFalse(Files.exists(temporaryDirectory.resolve("device-a")));
        adapter.closeResources();
    }

    @Test
    void missingApprovedTrustAnchorFailsClosedAndReleasesWatcher() throws Exception {
        Path root = temporaryDirectory.resolve("empty-pki");
        Files.createDirectory(root);
        OpcUaConnectionAdapter adapter = adapter("device-a", root);
        assertThrows(Exception.class, () -> validator(adapter));
        assertTrue(ReflectionTestUtils.getField(adapter, "trustListManager") == null);
        adapter.closeResources();
        assertTrue(Files.isDirectory(root.resolve("device-a/trusted/certs")));
    }

    @Test
    void invalidTrustFileCannotMakeInitializationSucceed() throws Exception {
        Path root = temporaryDirectory.resolve("invalid-pki");
        Path trusted = root.resolve("device-a/trusted/certs");
        Files.createDirectories(trusted);
        Files.writeString(trusted.resolve("invalid.der"), "不是证书", StandardCharsets.UTF_8);
        OpcUaConnectionAdapter adapter = adapter("device-a", root);
        assertThrows(Exception.class, () -> validator(adapter));
        assertTrue(ReflectionTestUtils.getField(adapter, "trustListManager") == null);
        adapter.closeResources();
    }

    @Test
    void repeatedInitializationDoesNotReuseAClosedTrustList() throws Exception {
        KeyPair key = keyPair();
        X509Certificate ca = certificate(key, null, null, true, null, APPLICATION_URI, 1);
        Path root = temporaryDirectory.resolve("reconnect-pki");
        Path trusted = root.resolve("device-a/trusted/certs");
        Files.createDirectories(trusted);
        Path certificateFile = trusted.resolve("ca.der");
        writeCertificate(certificateFile, ca);
        OpcUaConnectionAdapter adapter = adapter("device-a", root);
        try {
            validator(adapter);
            assertEquals(List.of(ca), trustManager(adapter).getTrustedCertificates());
            adapter.closeResources();
            Files.delete(certificateFile);
            assertThrows(Exception.class, () -> validator(adapter));
            assertTrue(ReflectionTestUtils.getField(adapter, "trustListManager") == null);
        } finally {
            adapter.closeResources();
        }
    }

    @Test
    void secureEndpointMustNotFallbackToAnInsecureOrDifferentPolicy() throws Exception {
        Path root = temporaryDirectory.resolve("pki");
        Files.createDirectory(root);
        OpcUaConnectionAdapter adapter = adapter("device-a", root);
        ReflectionTestUtils.setField(adapter, "securityPolicy", SecurityPolicy.Basic256Sha256.getUri());
        ReflectionTestUtils.setField(adapter, "securityMode", MessageSecurityMode.SignAndEncrypt);
        EndpointDescription insecure = new EndpointDescription("opc.tcp://127.0.0.1:4840", null,
                ByteString.NULL_VALUE, MessageSecurityMode.None, SecurityPolicy.None.getUri(),
                new UserTokenPolicy[0], null, Unsigned.ubyte(0));
        assertThrows(Exception.class, () -> ReflectionTestUtils.invokeMethod(adapter, "selectEndpoint", List.of(insecure)));
        adapter.closeResources();
    }

    @Test
    void secureEndpointWithoutServerApplicationUriCannotSkipUriValidation() throws Exception {
        Path root = temporaryDirectory.resolve("pki");
        Files.createDirectory(root);
        OpcUaConnectionAdapter adapter = adapter("device-a", root);
        ReflectionTestUtils.setField(adapter, "securityPolicy", SecurityPolicy.Basic256Sha256.getUri());
        ReflectionTestUtils.setField(adapter, "securityMode", MessageSecurityMode.SignAndEncrypt);
        EndpointDescription missingIdentity = new EndpointDescription("opc.tcp://127.0.0.1:4840", null,
                ByteString.NULL_VALUE, MessageSecurityMode.SignAndEncrypt, SecurityPolicy.Basic256Sha256.getUri(),
                new UserTokenPolicy[0], null, Unsigned.ubyte(1));
        assertThrows(Exception.class, () -> ReflectionTestUtils.invokeMethod(adapter, "selectEndpoint", List.of(missingIdentity)));
        adapter.closeResources();
    }

    @Test
    void securePolicyCannotOptOutOfServerCertificateValidation() throws Exception {
        Path root = temporaryDirectory.resolve("pki");
        Files.createDirectory(root);
        OpcUaConnectionAdapter adapter = adapter("device-a", root);
        DeviceConnection connection = (DeviceConnection) ReflectionTestUtils.getField(adapter, "config");
        Map<String, Object> insecureConfig = new HashMap<>(connection.getExtJson());
        insecureConfig.put("trustAllServerCert", true);
        connection.setExtJson(insecureConfig);
        assertThrows(Exception.class, () -> validator(adapter));
        assertFalse(Files.exists(root.resolve("device-a")));
        adapter.closeResources();
    }

    @Test
    void failedEndpointDiscoveryLeavesNoClientOrTrustWatcher() throws Exception {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unusedPort = socket.getLocalPort();
        }
        Path root = temporaryDirectory.resolve("pki");
        Files.createDirectory(root);
        OpcUaConnectionAdapter adapter = adapter("device-a", root);
        DeviceConnection connection = (DeviceConnection) ReflectionTestUtils.getField(adapter, "config");
        connection.setUrl("opc.tcp://127.0.0.1:" + unusedPort);
        try {
            assertThrows(Exception.class, adapter::connect);
            assertNull(adapter.getClient());
            assertNull(ReflectionTestUtils.getField(adapter, "trustListManager"));
        } finally {
            adapter.closeResources();
            adapter.closeResources();
        }
    }

    @Test
    void usernameTokenEncryptionMustNotUseAnInsecureCertificateValidator() throws Exception {
        Path root = temporaryDirectory.resolve("pki");
        Path trusted = root.resolve("device-a/trusted/certs");
        Files.createDirectories(trusted);
        KeyPair key = keyPair();
        writeCertificate(trusted.resolve("ca.der"), certificate(key, null, null, true, null, APPLICATION_URI, 1));
        OpcUaConnectionAdapter adapter = adapter("device-a", root);
        DeviceConnection connection = (DeviceConnection) ReflectionTestUtils.getField(adapter, "config");
        Map<String, Object> usernameConfig = new HashMap<>(connection.getExtJson());
        usernameConfig.put("authType", "USERNAME");
        connection.setExtJson(usernameConfig);
        connection.setUsername("fixture-user");
        connection.setPassword("fixture-placeholder");
        UserTokenPolicy token = new UserTokenPolicy("user", UserTokenType.UserName, null, null, null);
        EndpointDescription endpoint = new EndpointDescription("opc.tcp://127.0.0.1:4840", null,
                ByteString.NULL_VALUE, MessageSecurityMode.SignAndEncrypt, SecurityPolicy.Basic256Sha256.getUri(),
                new UserTokenPolicy[]{token}, null, Unsigned.ubyte(1));
        CertificateValidator certificateValidator = validator(adapter);
        UsernameProvider provider = ReflectionTestUtils.invokeMethod(adapter, "resolveIdentityProvider",
                endpoint, null, certificateValidator);
        assertSame(certificateValidator, ReflectionTestUtils.getField(provider, "certificateValidator"));
        adapter.closeResources();
    }

    private static OpcUaConnectionAdapter adapter(String id, Path root) {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId(id);
        DeviceConnection connection = new DeviceConnection();
        connection.setDeviceId(id);
        connection.setConnectionId(id);
        Map<String, Object> config = new HashMap<>();
        config.put("pkiBaseDir", root.toString());
        config.put("securityPolicy", "Basic256Sha256");
        config.put("securityMode", "SignAndEncrypt");
        connection.setExtJson(config);
        return new OpcUaConnectionAdapter(device, connection);
    }

    private static CertificateValidator validator(OpcUaConnectionAdapter adapter) {
        return ReflectionTestUtils.invokeMethod(adapter, "resolveCertificateValidator");
    }

    private static FileBasedTrustListManager trustManager(OpcUaConnectionAdapter adapter) {
        FileBasedTrustListManager manager = (FileBasedTrustListManager) ReflectionTestUtils.getField(adapter, "trustListManager");
        assertNotNull(manager);
        return manager;
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static X509Certificate certificate(KeyPair subject, X509Certificate issuer, KeyPair issuerKey,
                                                boolean ca, String host, String uri, int relativeExpiry) throws Exception {
        Instant now = Instant.now();
        Date from = Date.from(now.minusSeconds(relativeExpiry > 0 ? 3600 : 7200));
        Date to = Date.from(now.plusSeconds(relativeExpiry > 0 ? 86400 : -3600));
        X500Name name = new X500Name("CN=" + (ca ? "隔离CA" : "隔离服务端"));
        X500Name issuerName = issuer == null ? name : new X500Name(issuer.getSubjectX500Principal().getName());
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                issuerName, java.math.BigInteger.valueOf(SERIAL.getAndIncrement()), from, to, name, subject.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(ca ? KeyUsage.keyCertSign | KeyUsage.cRLSign
                : KeyUsage.digitalSignature | KeyUsage.nonRepudiation | KeyUsage.keyEncipherment | KeyUsage.dataEncipherment));
        if (!ca) {
            builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
            builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName[]{
                    new GeneralName(GeneralName.dNSName, host), new GeneralName(GeneralName.uniformResourceIdentifier, uri)
            }));
        }
        return new JcaX509CertificateConverter().getCertificate(builder.build(
                new JcaContentSignerBuilder("SHA256withRSA").build(issuerKey == null
                        ? subject.getPrivate() : issuerKey.getPrivate())));
    }

    private static void writeCertificate(Path path, X509Certificate certificate) throws Exception {
        Files.write(path, certificate.getEncoded());
    }
}
