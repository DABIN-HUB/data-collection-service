package com.wangbin.collector.core.connection.adapter;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.domain.enums.ConnectionStatus;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import lombok.extern.slf4j.Slf4j;
import org.apache.plc4x.java.DefaultPlcDriverManager;
import org.apache.plc4x.java.api.PlcConnection;
import org.apache.plc4x.java.api.PlcDriver;
import org.apache.plc4x.java.modbus.readwrite.ModbusTcpADU;
import org.apache.plc4x.java.modbus.tcp.ModbusTcpDriver;
import org.apache.plc4x.java.spi.Plc4xNettyWrapper;
import org.apache.plc4x.java.spi.connection.ProtocolStackConfigurer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * PLC4X-backed Modbus TCP 适配器.
 */
@Slf4j
public class Plc4xModbusTcpConnectionAdapter extends AbstractConnectionAdapter<PlcConnection> {

    private volatile PlcConnection connection;
    private volatile PlcConnection invalidConnection;
    private volatile BooleanSupplier connectionPermit = () -> true;
    private ConnectionResources connectionResources;
    private String connectionString;

    /**
     * 创建当前组件实例。
     */
    public Plc4xModbusTcpConnectionAdapter(DeviceInfo deviceInfo, DeviceConnection config) {
        super(deviceInfo, config);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected synchronized void doConnect() throws Exception {
        // 失活后的重连也先释放旧对象，不能覆盖唯一的待清理引用。
        doDisconnect();
        requireConnectionPermit();
        connectionString = buildConnectionString();
        PlcDriver driver = new DefaultPlcDriverManager().getDriverForUrl(connectionString);
        if (driver instanceof ModbusTcpDriver) {
            ConnectionResources resources = new ConnectionResources();
            connectionResources = resources;
            driver = new ModbusTcpDriver() {
                @Override
                protected ProtocolStackConfigurer<ModbusTcpADU> getStackConfigurer() {
                    ProtocolStackConfigurer<ModbusTcpADU> original = super.getStackConfigurer();
                    return (configuration, pipeline, authentication, passive, listeners) -> {
                        try {
                            return original.configurePipeline(configuration, pipeline, authentication, passive, listeners);
                        } finally {
                            resources.bind(pipeline);
                        }
                    };
                }
            };
        }
        // DriverManager.getConnection 内部就会建连，失败时无法交还已创建的资源。
        requireConnectionPermit();
        connection = driver.getConnection(connectionString);
        try {
            requireConnectionPermit();
            connection.connect();
            invalidConnection = null;
        } catch (Exception error) {
            try {
                doDisconnect();
            } catch (Exception cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw error;
        }
        setConnectionParam("connectionString", connectionString);
        log.info("PLC4X Modbus TCP 连接 已创建:{}", connectionString);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected synchronized void doDisconnect() throws Exception {
        PlcConnection current = connection;
        if (current == null) {
            return;
        }
        try {
            current.close();
        } catch (RejectedExecutionException error) {
            // 远端已关闭且本连接资源已释放时，PLC4X 的迟到事件投递不再有目标。
            if (connectionResources == null || !connectionResources.isReleased()) {
                throw error;
            }
            log.debug("PLC4X Modbus 已释放连接的事件循环已关闭：{}", getDeviceId());
        }
        if (connectionResources != null && connectionResources.channel != null) {
            connectionResources.released.get(resolveRequestTimeout(), TimeUnit.MILLISECONDS);
        }
        connection = null;
        connectionResources = null;
    }

    /** 只捕获本次连接的公开生命周期对象，不访问或关闭共享定时器。 */
    private static final class ConnectionResources {
        private volatile Channel channel;
        private final CompletableFuture<Void> released = new CompletableFuture<>();

        private void bind(ChannelPipeline pipeline) {
            Plc4xNettyWrapper<?> wrapper = pipeline.get(Plc4xNettyWrapper.class);
            if (wrapper == null) {
                return;
            }
            channel = pipeline.channel();
            var context = pipeline.context(wrapper);
            // 配置阶段早于 TCP 建连；失活和初始化失败不能绕过本 wrapper 的 close。
            channel.closeFuture().addListener(closed -> {
                ChannelPromise promise = context.newPromise();
                try {
                    wrapper.close(context, promise);
                    promise.addListener(result -> {
                        if (result.isSuccess()) {
                            released.complete(null);
                        } else {
                            released.completeExceptionally(result.cause());
                        }
                    });
                } catch (Exception error) {
                    released.completeExceptionally(error);
                }
            });
        }

        private boolean isReleased() {
            Channel captured = channel;
            return captured != null && !captured.isOpen() && captured.closeFuture().isDone()
                    && captured.eventLoop().isShuttingDown()
                    && released.isDone() && !released.isCompletedExceptionally();
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doHeartbeat() {
        if (connection == null || !connection.isConnected()) {
            throw new IllegalStateException("PLC4X Modbus TCP connection is not active");
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doAuthenticate() {
        // Modbus TCP does not require a separate 认证 phase here.
    }

    @Override
    public PlcConnection getClient() {
        return connection;
    }

    /** 许可只读所有权/代次的 volatile 状态，不取得配置锁、CM I/O 锁或采集器 monitor。 */
    public void setConnectionPermit(BooleanSupplier permit) {
        connectionPermit = permit != null ? permit : () -> true;
    }

    private void requireConnectionPermit() {
        if (!connectionPermit.getAsBoolean()) throw new CancellationException("原连接所有者已退役，拒绝建连");
    }

    /** 纯状态退役可位于源提交门内；物理清理必须在 guard/config 门外。 */
    public synchronized boolean markClientInvalid(PlcConnection expected, Throwable failure) {
        if (expected == null || connection != expected || invalidConnection == expected) {
            return false;
        }
        invalidConnection = expected;
        status = ConnectionStatus.ERROR;
        metrics.setStatus(status);
        metrics.setLastError(failure.toString());
        metrics.setLastErrorTime(System.currentTimeMillis());
        errors.incrementAndGet();
        return true;
    }

    public synchronized void closeInvalidClient(PlcConnection expected) throws Exception {
        if (expected != null && connection == expected && invalidConnection == expected) doDisconnect();
    }

    /** 保留既有比较关闭入口，旧请求清理不能关闭 CM 已换出的新 client。 */
    public boolean invalidateClient(PlcConnection expected, Throwable failure) throws Exception {
        boolean marked = markClientInvalid(expected, failure);
        if (marked) closeInvalidClient(expected);
        return marked;
    }

    /** 失败交接允许刚退役的原连接，但绝不能借用已替换的新 client。 */
    public synchronized boolean ownsClient(PlcConnection expected) {
        return expected != null && (connection == expected
                || (connection == null && invalidConnection == expected));
    }

    @Override
    public boolean isConnected() {
        PlcConnection current = connection;
        return super.isConnected() && current != null && current != invalidConnection && current.isConnected();
    }

    public String getConnectionString() {
        return connectionString;
    }

    /**
     * 创建并返回业务对象。
     */
    private String buildConnectionString() {
        String configured = config.getString("plc4xConnectionString", null);
        if (hasText(configured)) {
            return configured;
        }

        String host = resolveHost();
        Integer port = resolvePort();
        if (!hasText(host) || port == null || port <= 0) {
            throw new IllegalStateException("Invalid Modbus TCP connection host/port");
        }

        List<String> options = new ArrayList<>();
        options.add("request-timeout=" + resolveRequestTimeout());
        options.add("default-unit-identifier=" + config.getInt("slaveId", 1));
        options.add("max-registers-per-request=" + config.getInt("maxRegistersPerRequest", 125));
        options.add("max-coils-per-request=" + config.getInt("maxCoilsPerRequest", 2000));

        String pingAddress = config.getString("pingAddress", null);
        if (hasText(pingAddress)) {
            options.add("ping-address=" + pingAddress);
        }

        StringBuilder builder = new StringBuilder("modbus-tcp:tcp://")
                .append(host)
                .append(':')
                .append(port);
        if (!options.isEmpty()) {
            builder.append('?').append(String.join("&", options));
        }
        return builder.toString();
    }

    /**
     * 解析或转换业务数据。
     */
    private long resolveRequestTimeout() {
        Integer readTimeout = config.getReadTimeout();
        if (readTimeout != null && readTimeout > 0) {
            return readTimeout;
        }
        Integer timeout = config.getTimeout();
        if (timeout != null && timeout > 0) {
            return timeout;
        }
        return 5000L;
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
