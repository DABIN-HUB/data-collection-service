package com.wangbin.collector.core.connection.adapter;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.domain.ethernetip.EtherNetIpConnectionContract;
import lombok.extern.slf4j.Slf4j;
import org.apache.plc4x.java.DefaultPlcDriverManager;
import org.apache.plc4x.java.api.PlcConnection;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 定义当前模块的业务组件。
 */
@Slf4j
public class EtherNetIpConnectionAdapter extends AbstractConnectionAdapter<PlcConnection> {

    private static final String EIP_PROTOCOL_LOGIC =
            "org.apache.plc4x.java.eip.base.protocol.EipProtocolLogic";
    private static final int MAX_DIAGNOSTIC_CAUSES = 64;

    private PlcConnection connection;
    private String connectionString;

    /**
     * 创建当前组件实例。
     */
    public EtherNetIpConnectionAdapter(DeviceInfo deviceInfo, DeviceConnection config) {
        super(deviceInfo, config);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doConnect() throws Exception {
        connectionString = buildConnectionString();
        String diagnosticPort = resolveDiagnosticPort();
        String stage = "DRIVER_CREATE";
        try {
            // 管理器的 getConnection 会立即建连；先取得客户端，才能关闭握手失败的资源。
            connection = new DefaultPlcDriverManager().getDriverForUrl(connectionString)
                    .getConnection(connectionString);
            if (connection == null) {
                throw new IllegalStateException("PLC4X 未返回 EtherNet/IP 连接");
            }
            stage = "CONNECT";
            if (!connection.isConnected()) {
                connection.connect();
            }
        } catch (Exception exception) {
            String failureStage = resolveFailureStage(exception, stage);
            Exception cleanupFailure = null;
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception closeException) {
                    cleanupFailure = closeException;
                } finally {
                    connection = null;
                }
            }
            connectionString = null;
            IllegalStateException failure = new IllegalStateException(
                    "PLC4X EtherNet/IP 建连失败 [deviceId=" + diagnosticDeviceId()
                            + ", protocol=ETHERNET_IP, port=" + diagnosticPort
                            + ", stage=" + failureStage + "]：" + failureHint(failureStage),
                    diagnosticCause(exception, new IdentityHashMap<>()));
            if (cleanupFailure != null) {
                failure.addSuppressed(diagnosticCause(cleanupFailure, new IdentityHashMap<>()));
            }
            throw failure;
        }
        setConnectionParam("connectionSource", hasText(config.getString("plc4xConnectionString", null))
                ? "EXPLICIT" : "AUTO");
        setConnectionParam("driver", "PLC4X");
        log.info("PLC4X EtherNet/IP 连接已创建，设备:{}", getDeviceId());
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doDisconnect() throws Exception {
        try {
            if (connection != null) {
                connection.close();
            }
        } finally {
            connection = null;
            connectionString = null;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doHeartbeat() {
        if (connection == null || !connection.isConnected()) {
            throw new IllegalStateException("PLC4X EtherNet/IP connection is not active");
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doAuthenticate() {
        // No additional 认证 phase for PLC4X EtherNet/IP connections.
    }

    @Override
    public PlcConnection getClient() {
        return connection;
    }

    @Override
    public boolean isConnected() {
        return super.isConnected() && connection != null && connection.isConnected();
    }

    public String getConnectionString() {
        return connectionString == null ? null : "logix:tcp://[已隐藏]";
    }

    /**
     * 从实际连接串仅提取端口，显式连接源不得沿用设备配置端口。
     */
    private String resolveDiagnosticPort() {
        try {
            int port = URI.create(connectionString.substring("logix:".length())).getPort();
            return Integer.toString(port < 0 ? 44818 : port);
        } catch (IllegalArgumentException exception) {
            return "UNKNOWN";
        }
    }

    private String diagnosticDeviceId() {
        String deviceId = getDeviceId();
        return deviceId == null ? "UNKNOWN"
                : deviceId.matches("[A-Za-z0-9._:-]{1,128}") ? deviceId : "[已隐藏]";
    }

    /**
     * 仅依据已安装驱动的实际异常和栈细分阶段，不能把所有握手错误归为 ListServices。
     */
    private String resolveFailureStage(Throwable failure, String fallback) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        String inferredStage = fallback;
        for (Throwable current = failure; current != null && visited.size() < MAX_DIAGNOSTIC_CAUSES
                && visited.add(current); current = current.getCause()) {
            if (current instanceof UnknownHostException) {
                return "ADDRESS_RESOLUTION";
            }
            if (current instanceof ConnectException || current instanceof NoRouteToHostException) {
                return "TCP_CONNECT";
            }
            for (StackTraceElement frame : current.getStackTrace()) {
                if (!EIP_PROTOCOL_LOGIC.equals(frame.getClassName())) {
                    continue;
                }
                // PLC4X 会包装成关闭通道异常；真实握手位置可能不在栈顶。
                // 注册会话也复用 ListServices 文案，不能仅根据消息推断阶段。
                String identifiedStage = switch (frame.getMethodName()) {
                    case "listServices", "lambda$3", "lambda$4" -> "LIST_SERVICES";
                    case "onConnectRegisterSession", "lambda$9" -> "REGISTER_SESSION";
                    case "onConnectOpenConnectionManager", "lambda$10", "lambda$14" -> "OPEN_CONNECTION_MANAGER";
                    default -> null;
                };
                if (identifiedStage != null) {
                    inferredStage = identifiedStage;
                    break;
                }
            }
        }
        return inferredStage;
    }

    private String failureHint(String stage) {
        return switch (stage) {
            case "LIST_SERVICES" -> "ListServices 阶段失败，尚未进入 Tag 读取；远端状态以实际报文为准";
            case "REGISTER_SESSION", "OPEN_CONNECTION_MANAGER" -> "协议会话握手失败，尚未进入 Tag 读取";
            case "ADDRESS_RESOLUTION", "TCP_CONNECT" -> "地址解析或 TCP 建连失败，请检查端点和网络";
            default -> "阶段尚不能进一步确认，请检查连接配置和设备状态";
        };
    }

    /**
     * 上层会遍历 cause 和 suppressed 输出日志；仅公开类型、栈和脱敏后的因果结构。
     */
    private static Throwable diagnosticCause(Throwable original, Map<Throwable, Throwable> copies) {
        if (original == null) {
            return null;
        }
        Throwable existing = copies.get(original);
        if (existing != null) {
            return existing;
        }
        if (copies.size() >= MAX_DIAGNOSTIC_CAUSES) {
            return new Exception("诊断因果链已截断，原始异常仅在内部保留");
        }
        DiagnosticCause copy = new DiagnosticCause(original);
        copies.put(original, copy);
        copy.initCause(diagnosticCause(original.getCause(), copies));
        for (Throwable suppressed : original.getSuppressed()) {
            copy.addSuppressed(diagnosticCause(suppressed, copies));
        }
        return copy;
    }

    /**
     * 原始异常只供进程内受控诊断，不提供 getter，也不进入异常序列化或公共日志链。
     */
    private static final class DiagnosticCause extends Exception {
        private static final long serialVersionUID = 1L;
        private final transient Throwable originalCause;

        private DiagnosticCause(Throwable originalCause) {
            super("causeType=" + originalCause.getClass().getName() + "，原始消息已隐藏");
            this.originalCause = originalCause;
            setStackTrace(originalCause.getStackTrace());
        }
    }

    /**
     * 创建并返回业务对象。
     */
    private String buildConnectionString() {
        EtherNetIpConnectionContract.validate(deviceInfo, config);
        String configured = config.getString("plc4xConnectionString", null);
        if (hasText(configured)) {
            return configured;
        }

        String host = resolveHost();
        Integer configuredPort = resolvePort();
        int port = configuredPort == null ? 44818 : configuredPort;
        List<String> options = new ArrayList<>();

        String communicationPath = firstNonBlank(
                config.getString("communicationPath", null),
                config.getString("communication-path", null)
        );
        if (!hasText(communicationPath)) {
            int backplane = EtherNetIpConnectionContract.integer(config, "backplane", 1, 1, 2);
            int slot = EtherNetIpConnectionContract.integer(config, "slot", 0, 0, 255);
            communicationPath = backplane + "," + slot;
        } else {
            // PLC4X 0.13.0 按逗号直接切分路由，不识别配置界面的方括号。
            communicationPath = EtherNetIpConnectionContract.normalizeRoute(communicationPath);
        }
        options.add("communication-path=" + communicationPath);

        if (hasConfig("bigEndian")) {
            options.add("big-endian=" + config.getBool("bigEndian", Boolean.TRUE));
        }
        if (hasConfig("forceUnconnectedOperation")) {
            options.add("force-unconnected-operation="
                    + config.getBool("forceUnconnectedOperation", Boolean.FALSE));
        }
        if (hasConfig("tcpKeepAlive")) {
            options.add("tcp.keep-alive=" + config.getBool("tcpKeepAlive", Boolean.TRUE));
        }
        if (hasConfig("tcpNoDelay")) {
            options.add("tcp.no-delay=" + config.getBool("tcpNoDelay", Boolean.TRUE));
        }

        // PLC4X 0.13.0 将 tcp.default-timeout 用于 TCP 建连，不能用读取超时填充。
        Integer connectTimeout = config.getConnectTimeout();
        if (connectTimeout != null) {
            options.add("tcp.default-timeout=" + connectTimeout);
        }

        StringBuilder builder = new StringBuilder("logix:tcp://")
                .append(host)
                .append(':')
                .append(port);
        if (!options.isEmpty()) {
            builder.append('?').append(String.join("&", options));
        }
        return builder.toString();
    }

    /**
     * 执行当前业务逻辑。
     */
    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean hasConfig(String key) {
        return (config.getExtJson() != null && config.getExtJson().containsKey(key))
                || (config.getAuthParams() != null && config.getAuthParams().containsKey(key));
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
