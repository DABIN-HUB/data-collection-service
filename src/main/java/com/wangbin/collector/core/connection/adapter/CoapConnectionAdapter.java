package com.wangbin.collector.core.connection.adapter;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.connection.dispatch.MessageBatchDispatcher;
import com.wangbin.collector.core.connection.dispatch.OverflowStrategy;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.californium.core.CoapClient;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * CoAP 连接适配器，将请求调度到批量分发线程中执行。
 */
@Slf4j
public class CoapConnectionAdapter extends AbstractConnectionAdapter<CoapClient> {

    private CoapClient baseClient;
    private String baseUri;
    private MessageBatchDispatcher<CoapRequest<?>> dispatcher;

    /**
     * 创建当前组件实例。
     */
    public CoapConnectionAdapter(DeviceInfo deviceInfo, DeviceConnection config) {
        super(deviceInfo, config);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doConnect() throws Exception {
        this.baseUri = resolveBaseUri();
        this.baseClient = new CoapClient(new URI(baseUri));
        this.baseClient.setTimeout(getRequestTimeout());
        startDispatcher();
        log.info("CoAP 客户端已建立: {}", baseUri);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doDisconnect() throws Exception {
        if (baseClient != null) {
            try {
                baseClient.shutdown();
            } catch (Exception e) {
                log.warn("关闭 CoAP 客户端异常", e);
            } finally {
                baseClient = null;
            }
        }
        stopDispatcher();
    }

    public String getBaseUri() {
        return baseUri;
    }

    public long getRequestTimeout() {
        if (config.getReadTimeout() != null && config.getReadTimeout() > 0) {
            return config.getReadTimeout();
        }
        return config.getTimeout() != null ? config.getTimeout() : 3000;
    }

    @Override
    public CoapClient getClient() {
        return baseClient;
    }

    /**
     * 创建并返回业务对象。
     */
    public CoapClient createClient(String uri) {
        String resolved = uri;
        if (resolved == null || resolved.isBlank()) {
            resolved = baseUri;
        }
        return new CoapClient(resolved);
    }

    /**
     * 处理当前业务流程。
     */
    public <T> CompletableFuture<T> submit(CoapCallable<T> callable) {
        Objects.requireNonNull(callable, "callable");
        if (dispatcher == null) {
            CompletableFuture<T> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException("CoAP 调度器未初始化"));
            return failed;
        }
        CoapRequest<T> request = new CoapRequest<>(callable);
        try {
            dispatcher.enqueue(request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            request.fail(e);
        }
        return request.future;
    }

    /**
     * 处理当前业务流程。
     */
    public <T> T execute(CoapCallable<T> callable, long timeoutMillis) throws Exception {
        long effectiveTimeout = timeoutMillis > 0 ? timeoutMillis : getRequestTimeout();
        try {
            return submit(callable).get(effectiveTimeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new TimeoutException("CoAP 操作超时(" + effectiveTimeout + "ms)");
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doSend(byte[] data) {
        throw new UnsupportedOperationException("CoAP 适配器不支持裸数据发送，请使用 execute/submit");
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected byte[] doReceive() {
        return null;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected byte[] doReceive(long timeout) {
        return null;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doHeartbeat() {
        // 由上层采集器按需探测
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doAuthenticate() {
        // 默认无认证
    }

    /**
     * 解析或转换业务数据。
     */
    private String resolveBaseUri() {
        if (config.getUrl() != null && !config.getUrl().isBlank()) {
            return config.getUrl();
        }
        String scheme = config.getStringConfig("scheme", "coap");
        return scheme + "://" + config.getHost() + ":" + config.getPort();
    }

    /**
     * 处理组件生命周期。
     */
    private void startDispatcher() {
        if (dispatcher != null) {
            return;
        }
        int capacity = Math.max(1, config.getMaxPendingMessages() != null ? config.getMaxPendingMessages() : 1024);
        int batchSize = Math.max(1, config.getDispatchBatchSize() != null ? config.getDispatchBatchSize() : 1);
        long flushInterval = config.getDispatchFlushInterval() != null ? config.getDispatchFlushInterval() : 0L;
        dispatcher = new MessageBatchDispatcher<>(capacity, batchSize, flushInterval,
                OverflowStrategy.from(config.getOverflowStrategy()));
        dispatcher.addListener(this::processRequests);
        dispatcher.start();
    }

    /**
     * 处理组件生命周期。
     */
    private void stopDispatcher() {
        if (dispatcher != null) {
            dispatcher.stop();
            dispatcher = null;
        }
    }

    /**
     * 处理当前业务流程。
     */
    private void processRequests(List<CoapRequest<?>> requests) {
        if (requests == null || requests.isEmpty()) {
            return;
        }
        for (CoapRequest<?> request : requests) {
            if (!isConnected()) {
                request.fail(new IllegalStateException("CoAP 客户端未就绪"));
                continue;
            }
            request.run(this);
        }
    }

    /**
     * 定义当前模块的业务契约。
     */
    @FunctionalInterface
    public interface CoapCallable<T> {
        /**
         * 执行当前业务逻辑。
         */
        T call(CoapConnectionAdapter adapter) throws Exception;
    }

    /**
     * 承载当前模块的数据传输内容。
     */
    private static final class CoapRequest<T> {
        private final CoapCallable<T> callable;
        private final CompletableFuture<T> future = new CompletableFuture<>();

        /**
         * 创建当前组件实例。
         */
        private CoapRequest(CoapCallable<T> callable) {
            this.callable = callable;
        }

        /**
         * 执行当前业务逻辑。
         */
        private void complete(T value) {
            future.complete(value);
        }

        /**
         * 构造标准业务结果。
         */
        private void fail(Throwable throwable) {
            future.completeExceptionally(throwable);
        }

        /**
         * 处理当前业务流程。
         */
        private void run(CoapConnectionAdapter adapter) {
            try {
                complete(callable.call(adapter));
            } catch (Exception e) {
                fail(e);
            }
        }
    }
}

