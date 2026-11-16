package com.wangbin.collector.core.connection.adapter;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.exception.CollectorException;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.milo.opcua.sdk.client.DiscoveryClient;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.OpcUaClientConfigBuilder;
import org.eclipse.milo.opcua.sdk.client.identity.AnonymousProvider;
import org.eclipse.milo.opcua.sdk.client.identity.IdentityProvider;
import org.eclipse.milo.opcua.sdk.client.identity.UsernameProvider;
import org.eclipse.milo.opcua.sdk.client.identity.X509IdentityProvider;
import org.eclipse.milo.opcua.stack.core.security.CertificateQuarantine;
import org.eclipse.milo.opcua.stack.core.security.CertificateValidator;
import org.eclipse.milo.opcua.stack.core.security.DefaultClientCertificateValidator;
import org.eclipse.milo.opcua.stack.core.security.FileBasedCertificateQuarantine;
import org.eclipse.milo.opcua.stack.core.security.FileBasedTrustListManager;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.util.validation.ValidationCheck;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.enumerated.UserTokenType;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.UserTokenPolicy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OPC UA 连接 适配器 backed by Milo {@link OpcUaClient}.
 */
@Slf4j
public class OpcUaConnectionAdapter extends AbstractConnectionAdapter<OpcUaClient> {

    private static final String OPC_POLICY_URI_PREFIX = "http://opcfoundation.org/UA/SecurityPolicy#";
    private static final Pattern CERT_PATTERN =
            Pattern.compile("-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----", Pattern.DOTALL);
    private static final Pattern PRIVATE_KEY_PATTERN =
            Pattern.compile("-----BEGIN PRIVATE KEY-----(.*?)-----END PRIVATE KEY-----", Pattern.DOTALL);
    private static final long MIN_TIMEOUT_MS = 5000L;
    /** X.509 证书 SAN 扩展中 URI 的通用名称类型编码。 */
    private static final int URI_SAN_TYPE = 6;
    private static final Set<ValidationCheck> CERTIFICATE_CHECKS = EnumSet.of(
            ValidationCheck.APPLICATION_URI, ValidationCheck.HOSTNAME, ValidationCheck.VALIDITY,
            ValidationCheck.KEY_USAGE_END_ENTITY, ValidationCheck.EXTENDED_KEY_USAGE_END_ENTITY);
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);

    private volatile OpcUaClient client;
    private FileBasedTrustListManager trustListManager;
    private String endpointUrl;
    private String securityPolicy;
    private MessageSecurityMode securityMode;

    /**
     * 创建当前组件实例。
     */
    public OpcUaConnectionAdapter(DeviceInfo deviceInfo, DeviceConnection config) {
        super(deviceInfo, config);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doConnect() throws Exception {
        try {
            connectAndInitialize();
        } catch (Exception e) {
            cleanupClient();
            throw e;
        }
    }

    private void connectAndInitialize() throws Exception {
        this.endpointUrl = resolveEndpointUrl();
        this.securityPolicy = resolveSecurityPolicy();
        this.securityMode = resolveSecurityMode();

        List<EndpointDescription> endpoints = discoverEndpoints(endpointUrl);
        EndpointDescription endpoint = selectEndpoint(endpoints);

        ClientKeyMaterial clientKeyMaterial = loadClientKeyMaterial();
        OpcUaClientConfigBuilder builder = new OpcUaClientConfigBuilder();
        long requestTimeout = resolveRequestTimeout();
        builder.setEndpoint(endpoint);
        builder.setRequestTimeout(Unsigned.uint(requestTimeout));
        CertificateValidator certificateValidator = resolveCertificateValidator();
        builder.setCertificateValidator(certificateValidator);
        applyClientCertificates(builder, clientKeyMaterial);
        builder.setIdentityProvider(resolveIdentityProvider(endpoint, clientKeyMaterial, certificateValidator));

        client = OpcUaClient.create(builder.build());
        long connectTimeout = resolveConnectTimeout();
        CompletableFuture<OpcUaClient> connectAttempt = client.connectAsync();
        try {
            connectAttempt.get(connectTimeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            connectAttempt.cancel(true);
            throw CollectorException.connectionException(
                    "OPC UA连接超时(" + connectTimeout + "ms)", currentDeviceId());
        }

        connectionParams.put("endpointUrl", endpointUrl);
        connectionParams.put("securityPolicy", securityPolicy);
        connectionParams.put("securityMode", securityMode != null ? securityMode.name() : MessageSecurityMode.None.name());
        connectionParams.put("authType", resolveAuthType().name());
        connectionParams.put("requestTimeoutMs", requestTimeout);
        connectionParams.put("connectTimeoutMs", connectTimeout);

        cacheNamespaceInfo();
        metrics.setStatus(getStatus());
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doDisconnect() {
        cleanupClient();
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doHeartbeat() {
        if (client == null) {
            throw new IllegalStateException("OPC UA client is not connected");
        }
        try {
            NodeId timeNodeId = NodeId.parse("i=2258");
            DataValue value = client.readValue(0, TimestampsToReturn.Both, timeNodeId);
            if (value == null || value.getValue() == null) {
                throw new IllegalStateException("OPC UA heartbeat failed: No response from server");
            }
        } catch (Exception e) {
            throw new IllegalStateException("OPC UA heartbeat failed: " + e.getMessage(), e);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doAuthenticate() {
        // OPC UA 认证 handled during 连接 handshake.
    }

    @Override
    public OpcUaClient getClient() {
        return client;
    }

    /**
     * 解析或转换业务数据。
     */
    private String resolveEndpointUrl() {
        if (config.getUrl() != null && !config.getUrl().isBlank()) {
            return config.getUrl();
        }
        String override = config.getStringConfig("endpointUrl",
                config.getStringConfig("endpoint", null));
        if (override != null && !override.isBlank()) {
            return override;
        }
        if (config.getHost() != null && !config.getHost().isBlank()) {
            int port = config.getPort() != null && config.getPort() > 0 ? config.getPort() : 4840;
            return "opc.tcp://" + config.getHost() + ":" + port;
        }
        throw new IllegalArgumentException("OPC UA endpointUrl is required");
    }

    /**
     * 解析或转换业务数据。
     */
    private String resolveSecurityPolicy() {
        String text = config.getStringConfig("securityPolicy", "None");
        if (text.startsWith(OPC_POLICY_URI_PREFIX)) {
            return text;
        }
        for (SecurityPolicy value : SecurityPolicy.values()) {
            if (value.getUri().equalsIgnoreCase(text) || value.name().equalsIgnoreCase(text)) {
                return value.getUri();
            }
        }
        return text;
    }

    /**
     * 解析或转换业务数据。
     */
    private MessageSecurityMode resolveSecurityMode() {
        String text = firstNonBlank(
                config.getStringConfig("securityMode", null),
                MessageSecurityMode.None.name());
        for (MessageSecurityMode value : MessageSecurityMode.values()) {
            if (value.name().equalsIgnoreCase(text)) {
                return value;
            }
        }
        return MessageSecurityMode.None;
    }

    /**
     * 解析或转换业务数据。
     */
    private long resolveRequestTimeout() {
        Long timeout = config.getLongConfig("requestTimeoutMs", null);
        if (timeout == null || timeout <= 0) {
            timeout = config.getLongConfig("requestTimeout", null);
        }
        if ((timeout == null || timeout <= 0) && config.getReadTimeout() != null && config.getReadTimeout() > 0) {
            timeout = config.getReadTimeout().longValue();
        }
        if ((timeout == null || timeout <= 0) && config.getTimeout() != null && config.getTimeout() > 0) {
            timeout = config.getTimeout().longValue();
        }
        if (timeout == null || timeout <= 0) {
            timeout = MIN_TIMEOUT_MS;
        }
        return Math.max(MIN_TIMEOUT_MS, timeout);
    }

    /**
     * 解析或转换业务数据。
     */
    private long resolveConnectTimeout() {
        Long timeout = config.getLongConfig("connectTimeoutMs", null);
        if ((timeout == null || timeout <= 0) && config.getConnectTimeout() != null && config.getConnectTimeout() > 0) {
            timeout = config.getConnectTimeout().longValue();
        }
        if (timeout == null || timeout <= 0) {
            timeout = resolveRequestTimeout();
        }
        return Math.max(MIN_TIMEOUT_MS, timeout);
    }

    /**
     * 执行当前业务逻辑。
     */
    private List<EndpointDescription> discoverEndpoints(String url) throws Exception {
        long timeout = Math.max(MIN_TIMEOUT_MS, resolveRequestTimeout());
        try {
            return DiscoveryClient.getEndpoints(url).get(timeout, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            if (!url.endsWith("/discovery")) {
                return DiscoveryClient.getEndpoints(url + "/discovery").get(timeout, TimeUnit.MILLISECONDS);
            }
            throw e;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private EndpointDescription selectEndpoint(List<EndpointDescription> endpoints) {
        if (endpoints == null || endpoints.isEmpty()) {
            String deviceId = deviceInfo != null ? deviceInfo.getDeviceId() : null;
            throw new CollectorException("No OPC UA endpoints available", deviceId, null);
        }
        for (EndpointDescription endpoint : endpoints) {
            boolean policyMatch = endpoint.getSecurityPolicyUri() != null &&
                    endpoint.getSecurityPolicyUri().equalsIgnoreCase(securityPolicy);
            boolean modeMatch = securityMode == null || endpoint.getSecurityMode() == securityMode;
            if (policyMatch && modeMatch) {
                if (!SecurityPolicy.None.getUri().equalsIgnoreCase(securityPolicy)
                        && (endpoint.getServer() == null
                        || endpoint.getServer().getApplicationUri() == null
                        || endpoint.getServer().getApplicationUri().isBlank()
                        || endpoint.getServerCertificate() == null
                        || endpoint.getServerCertificate().isNullOrEmpty())) {
                    throw CollectorException.configException("安全 OPC UA 端点缺少服务端证书或应用 URI",
                            currentDeviceId(), null);
                }
                return endpoint;
            }
        }
        throw CollectorException.configException("服务器未提供所要求的 OPC UA 安全策略和消息模式", currentDeviceId(), null);
    }

    /**
     * 解析或转换业务数据。
     */
    private IdentityProvider resolveIdentityProvider(EndpointDescription endpoint, ClientKeyMaterial clientKeyMaterial,
                                                     CertificateValidator certificateValidator) {
        AuthType authType = resolveAuthType();
        switch (authType) {
            case USERNAME -> {
                Credentials credentials = resolveCredentials();
                if (credentials.username() == null || credentials.username().isBlank()) {
                    throw CollectorException.configException("OPC UA用户名未配置", currentDeviceId(), null);
                }
                if (!supportsIdentityToken(endpoint, UserTokenType.UserName)) {
                    throw CollectorException.configException("OPC UA端点不支持USERNAME认证", currentDeviceId(), null);
                }
                return new UsernameProvider(credentials.username(), credentials.password() != null ? credentials.password() : "",
                        certificateValidator);
            }
            case CERT -> {
                if (clientKeyMaterial == null) {
                    throw CollectorException.configException("CERT认证需要配置clientCertPath", currentDeviceId(), null);
                }
                if (!supportsIdentityToken(endpoint, UserTokenType.Certificate)) {
                    throw CollectorException.configException("OPC UA端点不支持CERT认证", currentDeviceId(), null);
                }
                return new X509IdentityProvider(clientKeyMaterial.leafCertificate(), clientKeyMaterial.keyPair().getPrivate());
            }
            case ANONYMOUS -> {
                if (!supportsIdentityToken(endpoint, UserTokenType.Anonymous)) {
                    throw CollectorException.configException("OPC UA端点不支持匿名认证，请切换认证方式", currentDeviceId(), null);
                }
                return new AnonymousProvider();
            }
            default -> throw new IllegalStateException("Unsupported authType " + authType);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean supportsIdentityToken(EndpointDescription endpoint, UserTokenType targetType) {
        UserTokenPolicy[] policies = endpoint.getUserIdentityTokens();
        if (policies == null || policies.length == 0) {
            return targetType == UserTokenType.Anonymous;
        }
        for (UserTokenPolicy policy : policies) {
            if (policy != null && policy.getTokenType() == targetType) {
                return true;
            }
        }
        return false;
    }

    /**
     * 清理或删除业务数据。
     */
    private void cleanupClient() {
        OpcUaClient previous = client;
        client = null;
        if (previous != null) {
            try {
                previous.disconnect();
            } catch (Exception e) {
                log.warn("失败 to 断开 OPC UA client", e);
            }
        }
        cleanupTrustListManager();
    }

    private void cleanupTrustListManager() {
        FileBasedTrustListManager manager = trustListManager;
        trustListManager = null;
        if (manager != null) {
            try {
                manager.close();
            } catch (IOException e) {
                log.warn("关闭 OPC UA 证书信任列表失败，设备: {}", currentDeviceId(), e);
            }
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private void cacheNamespaceInfo() {
        if (client == null) {
            return;
        }
        String namespaceUri = config.getStringConfig("namespaceUri", null);
        if (namespaceUri == null || namespaceUri.isBlank()) {
            return;
        }
        Number indexNumber = client.getNamespaceTable().getIndex(namespaceUri);
        if (indexNumber == null) {
            throw CollectorException.configException("服务器未暴露命名空间: " + namespaceUri, currentDeviceId(), null);
        }
        connectionParams.put("namespaceUri", namespaceUri);
        connectionParams.put("namespaceIndex", indexNumber.intValue());
    }

    /**
     * 解析或转换业务数据。
     */
    private CertificateValidator resolveCertificateValidator() throws IOException {
        boolean trustAll = Boolean.TRUE.equals(config.getBoolConfig("trustAllServerCert", false));
        if (trustAll) {
            if (!SecurityPolicy.None.getUri().equalsIgnoreCase(resolveSecurityPolicy())) {
                throw new IOException("安全 OPC UA 连接禁止信任所有服务端证书");
            }
            log.warn("OPC UA连接 {} 启用了信任所有服务端证书，仅建议在测试环境使用", currentDeviceId());
            return new CertificateValidator.InsecureCertificateValidator();
        }
        cleanupTrustListManager();
        Path baseDir = resolveSecurityBaseDir();
        // Milo 1.0.8 的参数依次为颁发者证书、颁发者 CRL、可信证书和可信 CRL；拒绝证书仅进隔离区。
        privateDirectory(baseDir.resolve("issuer"));
        privateDirectory(baseDir.resolve("trusted"));
        Path issuerCerts = privateDirectory(baseDir.resolve("issuer/certs"));
        Path issuerCrls = privateDirectory(baseDir.resolve("issuer/crl"));
        Path trustedCerts = privateDirectory(baseDir.resolve("trusted/certs"));
        Path trustedCrls = privateDirectory(baseDir.resolve("trusted/crl"));
        Path quarantineDir = privateDirectory(baseDir.resolve("quarantine"));
        FileBasedTrustListManager manager = new FileBasedTrustListManager(
                issuerCerts, issuerCrls, trustedCerts, trustedCrls);
        try {
            manager.initialize();
            if (!SecurityPolicy.None.getUri().equalsIgnoreCase(resolveSecurityPolicy())
                    && manager.getTrustedCertificates().isEmpty()) {
                throw new IOException("OPC UA 安全连接缺少已审核的可信证书");
            }
            CertificateQuarantine quarantine = new FileBasedCertificateQuarantine(quarantineDir.toFile());
            CertificateValidator validator = new DefaultClientCertificateValidator(manager, CERTIFICATE_CHECKS, quarantine);
            trustListManager = manager;
            return validator;
        } catch (IOException | RuntimeException e) {
            try {
                manager.close();
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private Path resolveSecurityBaseDir() throws IOException {
        String key = config.getConnectionId();
        if (key == null || key.isBlank()) {
            key = currentDeviceId();
        }
        if (key == null || !key.matches("[a-zA-Z0-9_-]+")) {
            throw new IOException("OPC UA 连接标识包含非法路径字符");
        }
        String configuredRoot = config.getStringConfig("pkiBaseDir", null);
        Path root;
        if (configuredRoot != null && !configuredRoot.isBlank()) {
            root = Paths.get(configuredRoot);
            if (!root.isAbsolute() || !Files.isDirectory(root)) {
                throw new IOException("OPC UA PKI 根目录必须是已经存在的绝对目录");
            }
        } else {
            root = Paths.get("target", "opcua", "security").toAbsolutePath().normalize();
            Files.createDirectories(root);
        }
        if (Files.isSymbolicLink(root)) {
            throw new IOException("OPC UA PKI 根目录不允许符号链接");
        }
        return privateDirectory(root.resolve(key));
    }

    private Path privateDirectory(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("OPC UA PKI 目录不允许符号链接");
        }
        Files.createDirectories(path);
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(path, PRIVATE_DIRECTORY_PERMISSIONS);
        }
        return path;
    }

    /**
     * 处理当前业务流程。
     */
    private void applyClientCertificates(OpcUaClientConfigBuilder builder, ClientKeyMaterial material)
            throws CertificateParsingException {
        boolean secureChannel = securityPolicy != null &&
                !SecurityPolicy.None.getUri().equalsIgnoreCase(securityPolicy);
        if (material != null) {
            builder.setKeyPair(material.keyPair());
            builder.setCertificate(material.leafCertificate());
            builder.setCertificateChain(material.certificateChain());
            if (secureChannel) {
                builder.setApplicationUri(clientApplicationUri(material.leafCertificate()));
            }
        } else if (secureChannel) {
            throw CollectorException.configException(
                    "安全策略 " + securityPolicy + " 需要配置客户端证书", currentDeviceId(), null);
        }
    }

    private String clientApplicationUri(X509Certificate certificate) throws CertificateParsingException {
        String applicationUri = null;
        var names = certificate.getSubjectAlternativeNames();
        if (names != null) {
            for (List<?> name : names) {
                if (name.size() >= 2 && Integer.valueOf(URI_SAN_TYPE).equals(name.get(0))
                        && name.get(1) instanceof String uri && !uri.isBlank()) {
                    if (applicationUri != null) {
                        throw CollectorException.configException("OPC UA 客户端证书含有多个应用 URI",
                                currentDeviceId(), null);
                    }
                    applicationUri = uri;
                }
            }
        }
        if (applicationUri == null) {
            throw CollectorException.configException("安全 OPC UA 客户端证书缺少应用 URI SAN",
                    currentDeviceId(), null);
        }
        return applicationUri;
    }

    /**
     * 查询并返回业务数据。
     */
    private ClientKeyMaterial loadClientKeyMaterial() throws Exception {
        String pathText = config.getStringConfig("clientCertPath", null);
        if (pathText == null || pathText.isBlank()) {
            return null;
        }
        Path path = Paths.get(pathText).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            throw CollectorException.configException("客户端证书文件不存在: " + path, currentDeviceId(), null);
        }
        String fileName = path.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".pfx") || fileName.endsWith(".p12")) {
            return loadPkcs12Material(path, config.getStringConfig("clientCertPassword", ""));
        }
        if (fileName.endsWith(".pem")) {
            return loadPemMaterial(path);
        }
        throw CollectorException.configException("不支持的客户端证书格式: " + path, currentDeviceId(), null);
    }

    /**
     * 查询并返回业务数据。
     */
    private ClientKeyMaterial loadPkcs12Material(Path path, String password) throws Exception {
        char[] pwd = password != null ? password.toCharArray() : new char[0];
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(path)) {
            keyStore.load(input, pwd);
        }
        Enumeration<String> aliases = keyStore.aliases();
        if (!aliases.hasMoreElements()) {
            throw CollectorException.configException("PKCS12证书未包含条目: " + path, currentDeviceId(), null);
        }
        String alias = aliases.nextElement();
        Key key = keyStore.getKey(alias, pwd);
        if (!(key instanceof PrivateKey privateKey)) {
            throw CollectorException.configException("PKCS12证书缺少私钥: " + path, currentDeviceId(), null);
        }
        Certificate[] chain = keyStore.getCertificateChain(alias);
        if (chain == null || chain.length == 0) {
            Certificate certificate = keyStore.getCertificate(alias);
            if (certificate != null) {
                chain = new Certificate[]{certificate};
            }
        }
        if (chain == null || chain.length == 0) {
            throw CollectorException.configException("PKCS12证书缺少证书链: " + path, currentDeviceId(), null);
        }
        X509Certificate[] x509Chain = new X509Certificate[chain.length];
        for (int i = 0; i < chain.length; i++) {
            x509Chain[i] = (X509Certificate) chain[i];
        }
        return new ClientKeyMaterial(new KeyPair(x509Chain[0].getPublicKey(), privateKey), x509Chain);
    }

    /**
     * 查询并返回业务数据。
     */
    private ClientKeyMaterial loadPemMaterial(Path path) throws Exception {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        Matcher certMatcher = CERT_PATTERN.matcher(text);
        List<X509Certificate> certificates = new ArrayList<>();
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        while (certMatcher.find()) {
            byte[] certBytes = Base64.getMimeDecoder().decode(stripWhitespace(certMatcher.group(1)));
            certificates.add((X509Certificate) factory.generateCertificate(new ByteArrayInputStream(certBytes)));
        }
        Matcher keyMatcher = PRIVATE_KEY_PATTERN.matcher(text);
        if (certificates.isEmpty() || !keyMatcher.find()) {
            throw CollectorException.configException("PEM文件缺少证书或私钥: " + path, currentDeviceId(), null);
        }
        byte[] keyBytes = Base64.getMimeDecoder().decode(stripWhitespace(keyMatcher.group(1)));
        String algorithm = certificates.get(0).getPublicKey().getAlgorithm();
        KeyFactory keyFactory = KeyFactory.getInstance(algorithm);
        PrivateKey privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        X509Certificate[] chain = certificates.toArray(new X509Certificate[0]);
        return new ClientKeyMaterial(new KeyPair(chain[0].getPublicKey(), privateKey), chain);
    }

    /**
     * 执行当前业务逻辑。
     */
    private String stripWhitespace(String text) {
        return text.replaceAll("\\s+", "");
    }

    /**
     * 解析或转换业务数据。
     */
    private AuthType resolveAuthType() {
        String raw = config.getStringConfig("authType", AuthType.ANONYMOUS.name());
        if (raw == null || raw.isBlank()) {
            return AuthType.ANONYMOUS;
        }
        try {
            return AuthType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            log.warn("未知认证类型 '{}', 降级到 ANONYMOUS", raw);
            return AuthType.ANONYMOUS;
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private Credentials resolveCredentials() {
        String username = config.getUsername();
        String password = config.getPassword();
        Map<String, String> authParams = config.getAuthParams();
        if ((username == null || username.isBlank()) && authParams != null) {
            username = authParams.get("username");
            password = authParams.get("password");
        }
        return new Credentials(username, password);
    }

    /**
     * 执行当前业务逻辑。
     */
    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /**
     * 执行当前业务逻辑。
     */
    private String currentDeviceId() {
        if (deviceInfo != null && deviceInfo.getDeviceId() != null) {
            return deviceInfo.getDeviceId();
        }
        return config.getDeviceId();
    }

    /**
     * 定义当前模块的不可变数据记录。
     */
    private record ClientKeyMaterial(KeyPair keyPair, X509Certificate[] certificateChain) {
        /**
         * 执行当前业务逻辑。
         */
        X509Certificate leafCertificate() {
            return certificateChain[0];
        }
    }

    /**
     * 定义当前模块的不可变数据记录。
     */
    private record Credentials(String username, String password) {}

    /**
     * 定义当前模块的枚举值。
     */
    private enum AuthType {
        ANONYMOUS,
        USERNAME,
        CERT
    }
}
