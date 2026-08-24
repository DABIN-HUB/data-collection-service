package com.wangbin.collector.core.config.protocol;

import com.wangbin.collector.core.collector.protocol.http.HttpCollector;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * HTTP 协议元数据提供者。
 */
@Component
@Order(180)
public class HttpProtocolDescriptorProvider implements ProtocolDescriptorProvider {

    @Override
    public void register(ProtocolDescriptorRegistry registry) {
        registry.registerPrimary(registry.descriptor("HTTP", "HTTP",
                "HTTP polling and request based collection.",
                List.of("HTTPS"), HttpCollector.class, "HTTP", 80,
                ProtocolAddressingMode.SYMBOLIC,
                true, true, false,
                List.of("/api/data", "http://device.local/status"),
                registry.fields(
                        registry.field("url", "string", "HTTP base URL", false, "http://127.0.0.1:8080", null, "connection"),
                        registry.field("host", "string", "Host", false, "127.0.0.1", null, "connection"),
                        registry.field("port", "number", "Port", false, "8080", null, "connection"),
                        registry.field("sslEnabled", "boolean", "Enable HTTPS", false, "false",
                                List.of("true", "false"), "security"),
                        registry.field("path", "string", "Base path", false, "", null, "request"),
                        registry.field("method", "select", "Request method", false, "POST",
                                List.of("GET", "POST", "PUT", "DELETE", "HEAD"), "request"),
                        registry.field("headers", "object", "Request headers", false, "{}", null, "request"),
                        registry.field("queryParams", "object", "Query parameters", false, "{}", null, "request"),
                        registry.field("sendEndpoint", "string", "Send endpoint", false, "/api/data", null, "request"),
                        registry.field("receiveEndpoint", "string", "Receive endpoint", false, "/api/receive", null, "request"),
                        registry.field("receiveMethod", "select", "Receive method", false, "GET",
                                List.of("GET", "POST", "PUT", "DELETE"), "request"),
                        registry.field("healthCheckPath", "string", "Health check path", false, "/health", null, "advanced"),
                        registry.field("heartbeatEndpoint", "string", "Heartbeat endpoint", false, "/health", null, "advanced"),
                        registry.field("username", "string", "Username", false, "", null, "security"),
                        registry.field("password", "password", "Password", false, "", null, "security"),
                        registry.field("authToken", "password", "Bearer token", false, "", null, "security"),
                        registry.field("authEndpoint", "string", "Auth endpoint", false, "/api/auth", null, "security"),
                        registry.field("authMethod", "select", "Auth method", false, "POST",
                                List.of("GET", "POST", "PUT", "DELETE"), "security"),
                        registry.field("proxyHost", "string", "Proxy host", false, "", null, "advanced"),
                        registry.field("proxyPort", "number", "Proxy port", false, "8080", null, "advanced"),
                        registry.field("deviceSecret", "password", "Device secret", false, "", null, "security"),
                        registry.field("authParams", "object", "Extended auth params", false, "{}", null, "security"),
                        registry.field("connectTimeout", "number", "Connect timeout (ms)", false, "10000", null, "advanced"),
                        registry.field("readTimeout", "number", "Read timeout (ms)", false, "5000", null, "advanced"))));

        registry.registerAlias("HTTPS", "HTTP", cfg -> {
            cfg.setSslEnabled(true);
            ProtocolDescriptorRegistry.applyDefaultPort(cfg, 443);
        });
    }
}
