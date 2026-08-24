package com.wangbin.collector.core.config.protocol;

import com.wangbin.collector.core.collector.protocol.websocket.WebSocketCollector;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * WebSocket 协议元数据提供者。
 */
@Component
@Order(190)
public class WebSocketProtocolDescriptorProvider implements ProtocolDescriptorProvider {

    @Override
    public void register(ProtocolDescriptorRegistry registry) {
        registry.registerPrimary(registry.descriptor("WEBSOCKET", "WebSocket",
                "WebSocket collection protocol.",
                List.of("WEBSOCKET_SSL"), WebSocketCollector.class, "WEBSOCKET", 80,
                ProtocolAddressingMode.SYMBOLIC,
                true, true, true,
                List.of("ws://127.0.0.1:8080/ws", "/ws/device"),
                registry.fields(
                        registry.field("url", "string", "WebSocket URL", false, "ws://127.0.0.1:8080/ws", null, "connection"),
                        registry.field("host", "string", "Host", false, "127.0.0.1", null, "connection"),
                        registry.field("port", "number", "Port", false, "8080", null, "connection"),
                        registry.field("sslEnabled", "boolean", "Enable WSS", false, "false",
                                List.of("true", "false"), "security"),
                        registry.field("path", "string", "Connect path", false, "/ws", null, "connection"),
                        registry.field("headers", "object", "Request headers", false, "{}", null, "request"),
                        registry.field("queryParams", "object", "Query parameters", false, "{}", null, "request"),
                        registry.field("username", "string", "Username", false, "", null, "security"),
                        registry.field("password", "password", "Password", false, "", null, "security"),
                        registry.field("authToken", "password", "Bearer token", false, "", null, "security"),
                        registry.field("connectTimeout", "number", "Connect timeout (ms)", false, "10000", null, "advanced"),
                        registry.field("readTimeout", "number", "Read timeout (ms)", false, "5000", null, "advanced"),
                        registry.field("writeTimeout", "number", "Write timeout (ms)", false, "5000", null, "advanced"),
                        registry.field("subprotocol", "string", "Subprotocol", false, "collector-v1", null, "advanced"),
                        registry.field("binaryMode", "boolean", "Binary mode", false, "false",
                                List.of("true", "false"), "advanced"),
                        registry.field("heartbeatInterval", "number", "Heartbeat interval (ms)", false, "60000", null, "advanced"),
                        registry.field("heartbeatMessage", "string", "Heartbeat message", false, "ping", null, "advanced"),
                        registry.field("heartbeatUsePing", "boolean", "Use ping frame", false, "true",
                                List.of("true", "false"), "advanced"),
                        registry.field("authWaitResponse", "boolean", "Wait for auth response", false, "true",
                                List.of("true", "false"), "security"),
                        registry.field("productKey", "string", "Product key", false, "", null, "security"),
                        registry.field("deviceSecret", "password", "Device secret", false, "", null, "security"),
                        registry.field("authParams", "object", "Extended auth params", false, "{}", null, "security"))));

        registry.registerAlias("WEBSOCKET_SSL", "WEBSOCKET", cfg -> {
            cfg.setSslEnabled(true);
            ProtocolDescriptorRegistry.applyDefaultPort(cfg, 443);
        });
    }
}
