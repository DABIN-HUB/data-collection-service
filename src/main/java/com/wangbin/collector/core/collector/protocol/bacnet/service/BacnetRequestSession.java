package com.wangbin.collector.core.collector.protocol.bacnet.service;

import lombok.extern.slf4j.Slf4j;

import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class BacnetRequestSession {

    private final AtomicLong requestRetryCount;
    private final AtomicLong requestTimeoutCount;
    private final BacnetClientSupport clientSupport;

    public BacnetRequestSession(AtomicLong requestRetryCount,
                                AtomicLong requestTimeoutCount,
                                BacnetClientSupport clientSupport) {
        this.requestRetryCount = requestRetryCount;
        this.requestTimeoutCount = requestTimeoutCount;
        this.clientSupport = clientSupport;
    }

    public <T> T execute(RequestExchange<T> exchange,
                         long timeoutMs,
                         long segmentTimeoutMs,
                         int retries,
                         String retryLabel) throws Exception {
        int attempts = Math.max(0, retries) + 1;
        int resolvedTimeout = resolveTimeout(timeoutMs);
        int resolvedSegmentTimeout = resolveTimeout(segmentTimeoutMs > 0 ? segmentTimeoutMs : timeoutMs);
        Exception lastFailure = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            exchange.beforeAttempt();
            exchange.sendRequest();
            long deadline = System.currentTimeMillis() + resolvedTimeout;
            while (System.currentTimeMillis() <= deadline) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                byte[] response = exchange.pollResponse(remaining, TimeUnit.MILLISECONDS, resolvedSegmentTimeout);
                if (response == null) {
                    break;
                }
                try {
                    return exchange.decode(response);
                } catch (Exception ex) {
                    if (clientSupport.isInvokeIdMismatch(ex)) {
                        clientSupport.recordInvokeIdMismatch();
                        lastFailure = ex;
                        continue;
                    }
                    throw ex;
                }
            }
            lastFailure = new SocketTimeoutException(exchange.timeoutMessage(resolvedTimeout));
            requestTimeoutCount.incrementAndGet();
            if (attempt < attempts) {
                requestRetryCount.incrementAndGet();
                log.debug("Retry {} after timeout, attempt={}/{}", retryLabel, attempt + 1, attempts);
            }
        }
        throw lastFailure;
    }

    private int resolveTimeout(long timeoutMs) {
        if (timeoutMs <= 0) {
            return 5000;
        }
        if (timeoutMs > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) timeoutMs;
    }

    public interface RequestExchange<T> {
        void beforeAttempt() throws Exception;

        void sendRequest() throws Exception;

        byte[] pollResponse(long timeout, TimeUnit unit, int segmentTimeoutMs) throws Exception;

        T decode(byte[] response) throws Exception;

        String timeoutMessage(int resolvedTimeoutMs);
    }
}
