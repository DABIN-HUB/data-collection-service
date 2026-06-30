package com.wangbin.collector.core.collector.protocol.bacnet.support;

import com.wangbin.collector.core.collector.protocol.bacnet.transport.BacnetSerialChannel;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class InMemoryBacnetSerialChannel implements BacnetSerialChannel {

    private final BlockingQueue<Byte> inbound = new LinkedBlockingQueue<>();
    private volatile InMemoryBacnetSerialChannel peer;
    private volatile boolean open;

    public static ChannelPair createPair() {
        InMemoryBacnetSerialChannel left = new InMemoryBacnetSerialChannel();
        InMemoryBacnetSerialChannel right = new InMemoryBacnetSerialChannel();
        left.peer = right;
        right.peer = left;
        return new ChannelPair(left, right);
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
        if (peer == null || !peer.open) {
            throw new IllegalStateException("In-memory serial peer is not open");
        }
        if (data == null) {
            return;
        }
        for (byte value : data) {
            peer.inbound.put(value);
        }
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

    private void ensureOpen() {
        if (!open) {
            throw new IllegalStateException("In-memory serial channel is not open");
        }
    }

    public record ChannelPair(InMemoryBacnetSerialChannel left, InMemoryBacnetSerialChannel right) {
    }
}