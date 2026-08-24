package com.wangbin.collector.core.connection.serial;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedSerialChannelManagerTest {

    @Test
    void shouldShareSameProtocolPortAndCloseAfterLastLease() throws Exception {
        AtomicInteger created = new AtomicInteger();
        MemorySerialChannel channel = new MemorySerialChannel();
        SharedSerialChannelManager manager = new SharedSerialChannelManager(endpoint -> {
            created.incrementAndGet();
            return channel;
        });
        SerialEndpoint endpoint = endpoint(2400);

        SharedSerialChannelManager.Lease first = manager.acquire(endpoint, "DLT645_2007");
        SharedSerialChannelManager.Lease second = manager.acquire(endpoint, "DLT645_2007");

        assertEquals(1, created.get());
        assertEquals(1, manager.activePortCount());
        first.close();
        assertTrue(channel.isOpen());
        second.close();
        assertFalse(channel.isOpen());
        assertEquals(0, manager.activePortCount());
    }

    @Test
    void shouldRejectConflictingSerialConfiguration() throws Exception {
        SharedSerialChannelManager manager = new SharedSerialChannelManager(endpoint -> new MemorySerialChannel());
        SharedSerialChannelManager.Lease lease = manager.acquire(endpoint(2400), "DLT645_2007");

        try {
            assertThrows(IllegalStateException.class,
                    () -> manager.acquire(endpoint(9600), "DLT645_2007"));
            assertThrows(IllegalStateException.class,
                    () -> manager.acquire(endpoint(2400), "IEC101"));
        } finally {
            lease.close();
        }
    }

    private SerialEndpoint endpoint(int baudRate) {
        return new SerialEndpoint("COM9", baudRate, 8, 1, "EVEN", 1000, 1000);
    }

    private static final class MemorySerialChannel implements SerialChannel {

        private boolean open;

        @Override
        public void open() {
            open = true;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void write(byte[] data) {
        }

        @Override
        public int read(byte[] buffer, int offset, int length, long timeoutMs) {
            return 0;
        }

        @Override
        public void close() {
            open = false;
        }
    }
}
