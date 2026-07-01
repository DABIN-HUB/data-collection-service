package com.wangbin.collector.core.collector.protocol.bacnet.support;

import com.wangbin.collector.core.collector.protocol.bacnet.transport.BacnetSerialChannel;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class InMemoryBacnetSerialBus {

    private final List<EndpointChannel> endpoints = new CopyOnWriteArrayList<>();
    private final Object broadcastLock = new Object();

    public EndpointChannel createEndpoint() {
        EndpointChannel endpoint = new EndpointChannel(this);
        endpoints.add(endpoint);
        return endpoint;
    }

    private void broadcast(EndpointChannel sender, byte[] data) throws Exception {
        synchronized (broadcastLock) {
            for (EndpointChannel endpoint : endpoints) {
                if (endpoint == sender || !endpoint.isOpen()) {
                    continue;
                }
                endpoint.accept(data);
            }
        }
    }

    public static final class EndpointChannel implements BacnetSerialChannel {

        private final InMemoryBacnetSerialBus bus;
        private final BlockingQueue<Byte> inbound = new LinkedBlockingQueue<>();
        private volatile boolean open;

        private EndpointChannel(InMemoryBacnetSerialBus bus) {
            this.bus = bus;
        }

        @Override
        public void open() {
            open = true;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void write(byte[] data) throws Exception {
            ensureOpen();
            if (data == null) {
                return;
            }
            bus.broadcast(this, data);
        }

        @Override
        public int read(byte[] buffer, int offset, int length, long timeoutMs) throws Exception {
            ensureOpen();
            if (buffer == null || length <= 0) {
                return 0;
            }
            int count = 0;
            long deadline = System.currentTimeMillis() + Math.max(1L, timeoutMs);
            while (count < length && System.currentTimeMillis() <= deadline) {
                long remaining = Math.max(1L, deadline - System.currentTimeMillis());
                Byte value = count == 0
                        ? inbound.poll(remaining, TimeUnit.MILLISECONDS)
                        : inbound.poll(1, TimeUnit.MILLISECONDS);
                if (value == null) {
                    break;
                }
                buffer[offset + count] = value;
                count++;
            }
            return count;
        }

        @Override
        public void close() {
            open = false;
        }

        private void accept(byte[] data) throws Exception {
            for (byte value : data) {
                inbound.put(value);
            }
        }

        private void ensureOpen() {
            if (!open) {
                throw new IllegalStateException("In-memory serial bus endpoint is not open");
            }
        }
    }
}
