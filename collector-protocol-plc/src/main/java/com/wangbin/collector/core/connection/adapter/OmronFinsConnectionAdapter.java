package com.wangbin.collector.core.connection.adapter;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.domain.enums.FinsTransportMode;
import com.wangbin.collector.core.collector.protocol.fins.domain.FinsConnectionConfig;
import com.wangbin.collector.core.collector.protocol.fins.transport.FinsSessionNodes;
import com.wangbin.collector.core.collector.protocol.fins.transport.FinsTcpTransport;
import com.wangbin.collector.core.collector.protocol.fins.transport.FinsTransportException;
import com.wangbin.collector.core.collector.protocol.fins.transport.FinsTransport;
import com.wangbin.collector.core.collector.protocol.fins.transport.FinsUdpTransport;
import lombok.extern.slf4j.Slf4j;

import java.io.EOFException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;

/** FINS 连接适配器；AUTO 仅选择 UDP，不在业务超时后切换传输方式。 */
@Slf4j
public class OmronFinsConnectionAdapter extends AbstractConnectionAdapter<DatagramSocket> {
    private volatile FinsTransport transport;
    private volatile FinsTransportMode configuredTransport = FinsTransportMode.AUTO;
    private volatile FinsTransportMode effectiveTransport = FinsTransportMode.UDP;
    private volatile FinsSessionNodes effectiveNodes;
    private long pendingSinceNanos;
    private long exchangeDeadlineNanos;

    public OmronFinsConnectionAdapter(DeviceInfo deviceInfo, DeviceConnection config) {
        super(deviceInfo, config);
    }

    @Override
    protected synchronized void doConnect() throws Exception {
        closeResources();
        FinsConnectionConfig settings = FinsConnectionConfig.from(config, deviceInfo);
        String host = settings.getHost();
        int port = settings.getPort();
        InetSocketAddress remote = new InetSocketAddress(host, port);
        configuredTransport = settings.getTransport();
        effectiveTransport = configuredTransport == FinsTransportMode.TCP
                ? FinsTransportMode.TCP : FinsTransportMode.UDP;
        try {
            FinsTransport created = effectiveTransport == FinsTransportMode.TCP
                    ? new FinsTcpTransport(remote, settings.getConnectTimeoutMs(),
                            settings.getMaxFrameSize(), settings.getLocalNode(), settings.getTimeoutMs())
                    : new FinsUdpTransport(remote, settings.getReceiveBufferSize(),
                            new FinsSessionNodes(settings.getLocalNode(), settings.getPlcNode()));
            transport = created;
            effectiveNodes = created.nodes();
            setConnectionParam("host", host);
            setConnectionParam("port", port);
            setConnectionParam("transport", effectiveTransport.name());
            log.info("OMRON FINS {} 连接已创建:{}:{}", effectiveTransport, host, port);
        } catch (Exception exception) {
            closeResources();
            if (exception instanceof FinsTransportException transportException) {
                throw transportException;
            }
            FinsTransportException.Code code = exception instanceof SocketTimeoutException
                    ? FinsTransportException.Code.CONNECT_TIMEOUT
                    : exception instanceof EOFException ? FinsTransportException.Code.REMOTE_CLOSED
                    : FinsTransportException.Code.TCP_HANDSHAKE_ERROR;
            throw new FinsTransportException(code, "FINS " + effectiveTransport + " connect failed", exception);
        }
    }

    @Override
    protected synchronized void doDisconnect() {
        closeResources();
    }

    /** 无条件清理；覆盖父类重连直接调用 doConnect 而可能绕过断开的情形。 */
    @Override
    public synchronized void closeResources() {
        FinsTransport previous = transport;
        transport = null;
        effectiveNodes = null;
        pendingSinceNanos = 0;
        exchangeDeadlineNanos = 0;
        if (previous != null) {
            previous.close();
        }
    }

    @Override
    protected void doHeartbeat() {
        if (transport == null || !transport.isOpen()) {
            throw new IllegalStateException("FINS transport is not active");
        }
    }

    @Override
    protected void doAuthenticate() {
        // FINS 传输层无额外认证阶段。
    }

    @Override
    protected synchronized void doSend(byte[] data) {
        try {
            long timeout = exchangeDeadlineNanos == 0
                    ? FinsConnectionConfig.from(config).getTimeoutMs()
                    : FinsTransport.remainingMillis(exchangeDeadlineNanos);
            requireTransport().send(data, timeout);
            pendingSinceNanos = System.nanoTime();
        } catch (Exception exception) {
            if (exception instanceof SocketTimeoutException) {
                closeResources();
                throw new FinsTransportException(FinsTransportException.Code.SEND_TIMEOUT,
                        "FINS " + effectiveTransport + " send deadline exceeded", exception);
            }
            if (exception instanceof FinsTransportException transportException) {
                throw transportException;
            }
            throw new IllegalStateException("FINS send failed", exception);
        }
    }

    @Override
    protected synchronized byte[] doReceive() {
        return doReceive(0);
    }

    @Override
    protected synchronized byte[] doReceive(long timeout) {
        try {
            long duration = timeout > 0 ? timeout : FinsConnectionConfig.from(config).getTimeoutMs();
            if (exchangeDeadlineNanos != 0) {
                duration = Math.min(duration, FinsTransport.remainingMillis(exchangeDeadlineNanos));
            } else if (pendingSinceNanos != 0) {
                long elapsed = Math.max(0, (System.nanoTime() - pendingSinceNanos) / 1_000_000);
                duration = Math.max(1, duration - elapsed);
            }
            return requireTransport().receive(duration);
        } catch (Exception exception) {
            if (exception instanceof SocketTimeoutException) {
                closeResources();
            }
            if (exception instanceof FinsTransportException transportException) {
                throw transportException;
            }
            FinsTransportException.Code code = exception instanceof SocketTimeoutException
                    ? FinsTransportException.Code.READ_TIMEOUT
                    : exception instanceof EOFException ? FinsTransportException.Code.REMOTE_CLOSED
                    : effectiveTransport == FinsTransportMode.TCP
                    ? FinsTransportException.Code.TCP_FRAME_ERROR
                    : FinsTransportException.Code.UDP_RECEIVE_ERROR;
            throw new FinsTransportException(code, "FINS " + effectiveTransport + " receive failed", exception);
        } finally {
            pendingSinceNanos = 0;
        }
    }

    private FinsTransport requireTransport() {
        FinsTransport current = transport;
        if (current == null || !current.isOpen()) {
            throw new IllegalStateException("FINS transport is not initialized");
        }
        return current;
    }

    /** 单请求串行发送与接收，不进行任何内部重连或 AUTO 降级。 */
    public synchronized byte[] exchange(byte[] request, long timeoutMs) throws Exception {
        long duration = timeoutMs > 0 ? timeoutMs : FinsConnectionConfig.from(config).getTimeoutMs();
        exchangeDeadlineNanos = FinsTransport.deadline(duration);
        try {
            send(request);
            return receive(duration);
        } finally {
            exchangeDeadlineNanos = 0;
        }
    }

    /** 未连接时返回 null；连接后返回不可变的有效节点。 */
    public FinsSessionNodes getEffectiveNodes() {
        return effectiveNodes;
    }

    public FinsTransportMode getEffectiveTransport() {
        return effectiveTransport;
    }

    public FinsTransportMode getConfiguredTransport() {
        return configuredTransport;
    }

    @Override
    public DatagramSocket getClient() {
        FinsTransport current = transport;
        return current == null ? null : current.udpSocket();
    }

    @Override
    public boolean isConnected() {
        FinsTransport current = transport;
        return super.isConnected() && current != null && current.isOpen();
    }

    @Override
    protected String resolveHost() {
        return config.getHost() != null && !config.getHost().isBlank()
                ? config.getHost().trim() : deviceInfo != null ? deviceInfo.getIpAddress() : null;
    }

    @Override
    protected Integer resolvePort() {
        return config.getPort() != null && config.getPort() > 0
                ? config.getPort() : deviceInfo != null ? deviceInfo.getPort() : null;
    }
}
