package com.wangbin.collector.core.connection.adapter;


import com.wangbin.collector.common.constant.CommonMapKeys;
import com.beanit.iec61850bean.ClientAssociation;
import com.beanit.iec61850bean.ClientEventListener;
import com.beanit.iec61850bean.ClientSap;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;

import java.net.ConnectException;
import java.net.InetAddress;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * IEC61850 连接 适配器.
 */
public class Iec61850ConnectionAdapter extends AbstractConnectionAdapter<ClientAssociation> {

    private static final int MAX_DIAGNOSTIC_CAUSES = 64;

    private ClientSap clientSap;
    private ClientAssociation association;
    private ClientEventListener clientEventListener;

    /**
     * 创建当前组件实例。
     */
    public Iec61850ConnectionAdapter(DeviceInfo deviceInfo, DeviceConnection config) {
        super(deviceInfo, config);
    }

    public void setClientEventListener(ClientEventListener clientEventListener) {
        this.clientEventListener = clientEventListener;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doConnect() throws Exception {
        String host = resolveHost();
        int port = resolvePort() != null ? resolvePort() : 102;
        int timeout = resolveTimeout();
        String stage = "CONFIGURATION";
        try {
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("IEC61850 host is required");
            }
            clientSap = new ClientSap();
            clientSap.setResponseTimeout(timeout);
            stage = "ADDRESS_RESOLUTION";
            InetAddress address = InetAddress.getByName(host);
            stage = "ASSOCIATE";
            // 保留 Beanit 的 ISO/COTP、ACSE 和 MMS 校验，不能接受错误的连接确认报文。
            association = clientSap.associate(address, port, null, clientEventListener);
            stage = "ASSOCIATE_SETUP";
            association.setResponseTimeout(timeout);
        } catch (Exception exception) {
            String failureStage = resolveFailureStage(exception, stage);
            Exception cleanupFailure = null;
            try {
                doDisconnect();
            } catch (Exception closeException) {
                cleanupFailure = closeException;
            }
            IllegalStateException failure = new IllegalStateException(
                    "IEC61850 建连失败 [deviceId=" + diagnosticDeviceId()
                            + ", protocol=IEC61850, port=" + port + ", stage=" + failureStage
                            + "]：" + failureHint(failureStage),
                    diagnosticCause(exception, new IdentityHashMap<>()));
            if (cleanupFailure != null) {
                failure.addSuppressed(diagnosticCause(cleanupFailure, new IdentityHashMap<>()));
            }
            throw failure;
        }
        connectionParams.put(CommonMapKeys.HOST, host);
        connectionParams.put(CommonMapKeys.PORT, port);
        connectionParams.put(CommonMapKeys.TIMEOUT, timeout);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doDisconnect() throws Exception {
        try {
            if (association != null) {
                association.close();
            }
        } finally {
            association = null;
            clientSap = null;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doHeartbeat() {
        if (association == null) {
            throw new IllegalStateException("IEC61850 association is not active");
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doAuthenticate() {
        // IEC61850 association handshake completes during 连接.
    }

    @Override
    public ClientAssociation getClient() {
        return association;
    }

    private String diagnosticDeviceId() {
        String deviceId = getDeviceId();
        return deviceId == null ? "UNKNOWN"
                : deviceId.matches("[A-Za-z0-9._:-]{1,128}") ? deviceId : "[已隐藏]";
    }

    /**
     * associate 包含多个建连层次；只有实际因果栈能确认时才标记 ISO/COTP 子阶段。
     */
    private String resolveFailureStage(Throwable failure, String fallback) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && visited.size() < MAX_DIAGNOSTIC_CAUSES
                && visited.add(current); current = current.getCause()) {
            if (current instanceof UnknownHostException) {
                return "ADDRESS_RESOLUTION";
            }
            if (current instanceof ConnectException || current instanceof NoRouteToHostException) {
                return "TCP_CONNECT";
            }
            for (StackTraceElement frame : current.getStackTrace()) {
                if ("com.beanit.jositransport.TConnection".equals(frame.getClassName())
                        && "startConnection".equals(frame.getMethodName())) {
                    return "ISO_COTP";
                }
            }
        }
        return fallback;
    }

    private String failureHint(String stage) {
        return switch (stage) {
            case "ISO_COTP" -> "ISO/COTP 连接确认阶段失败，未进入 MMS 模型读取；保留 Beanit 协议校验";
            case "ASSOCIATE" -> "关联失败，具体建连子阶段未确认，未进入 MMS 模型读取";
            case "ASSOCIATE_SETUP" -> "关联后参数设置失败，未进入 MMS 模型读取";
            case "ADDRESS_RESOLUTION", "TCP_CONNECT" -> "地址解析或 TCP 建连失败，未进入 MMS 模型读取";
            default -> "连接配置或客户端初始化失败，未进入 MMS 模型读取";
        };
    }

    /**
     * 上层会打印整个异常图；公开副本仅保留类型、栈与脱敏后的 cause、suppressed 结构。
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
     * 完整原始异常仅私有留存在内存，不提供 getter，避免上层日志或序列化泄露报文与凭据。
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
     * 解析或转换业务数据。
     */
    private int resolveTimeout() {
        if (config.getTimeout() != null && config.getTimeout() > 0) {
            return config.getTimeout();
        }
        if (config.getReadTimeout() != null && config.getReadTimeout() > 0) {
            return config.getReadTimeout();
        }
        return 10000;
    }
}
