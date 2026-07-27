package com.wangbin.collector.api.filter.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 鉴权配置.
 */
@Data
@ConfigurationProperties(prefix = "collector.auth")
public class AuthProperties {

    private boolean enabled = true;
    private List<String> permitAllPaths = new ArrayList<>(List.of("/health", "/actuator/**", "/admin/**"));
    private List<String> ipAllowList = new ArrayList<>();
    private List<String> trustedProxyRanges = new ArrayList<>();
    private boolean allowIpAuthentication;

    private String tokenHeader = "X-Collector-Token";
    private String serviceHeader = "X-Collector-Service";
    private String signatureHeader = "X-Collector-Signature";
    private String timestampHeader = "X-Collector-Timestamp";
    private String keyVersionHeader = "X-Collector-Key-Version";
    private String nonceHeader = "X-Collector-Nonce";
    private int maxSignedBodyBytes = 1_048_576;

    /**
     * 允许的最大时间偏移（秒），防止重放。
     */
    private long maxSkewSeconds = 300;

    /**
     * 运维操作令牌: token -> 描述.
     */
    private Map<String, String> opsTokens = new LinkedHashMap<>();
    private List<AuthScope> defaultOpsScopes = new ArrayList<>(List.of(
            AuthScope.VIEW,
            AuthScope.DEVICE_CONTROL,
            AuthScope.CONFIG_MANAGE,
            AuthScope.SECURITY_MANAGE,
            AuthScope.EDGE_INGEST));
    private Map<String, List<AuthScope>> opsScopes = new LinkedHashMap<>();
    private List<AccessRule> accessRules = new ArrayList<>();
    private AuthScope defaultRequiredScope = AuthScope.VIEW;

    /**
     * 服务之间调用凭据: serviceId -> 配置.
     */
    private Map<String, ServiceClient> serviceClients = new LinkedHashMap<>();

    @Data
    public static class ServiceClient {
        private boolean enabled = true;
        private boolean requireSignature = true;
        private boolean allowIpFallback;
        private long maxSkewSeconds = -1;
        private String defaultKey;
        private Map<String, String> keys = new LinkedHashMap<>();
        private List<String> allowIps = new ArrayList<>();
        private List<AuthScope> scopes = new ArrayList<>(List.of(AuthScope.VIEW));

        public String resolveSecret(String requestedVersion) {
            if (!CollectionUtils.isEmpty(keys)) {
                if (StringUtils.hasText(requestedVersion) && keys.containsKey(requestedVersion)) {
                    return keys.get(requestedVersion);
                }
                if (StringUtils.hasText(defaultKey) && keys.containsKey(defaultKey)) {
                    return keys.get(defaultKey);
                }
                return keys.values().iterator().next();
            }
            return null;
        }

        public long resolveMaxSkew(long defaultSkewSeconds) {
            return maxSkewSeconds > 0 ? maxSkewSeconds : defaultSkewSeconds;
        }
    }

    @Data
    public static class AccessRule {
        private List<String> methods = new ArrayList<>();
        private List<String> paths = new ArrayList<>();
        private AuthScope requiredScope = AuthScope.VIEW;
    }

    public List<AuthScope> resolveOpsScopes(String label) {
        List<AuthScope> configured = opsScopes.get(label);
        return CollectionUtils.isEmpty(configured) ? defaultOpsScopes : configured;
    }
}
