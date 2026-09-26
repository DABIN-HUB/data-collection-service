package com.wangbin.collector.core.connection.dispatch;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MessageBatchDispatcherTest {

    @Test
    void dropLatestRejectsNewItemWhenCapacityIsFull() throws Exception {
        try (MessageBatchDispatcher<String> dispatcher = new MessageBatchDispatcher<>(1, 1, 0,
                OverflowStrategy.DROP_LATEST)) {
            assertTrue(dispatcher.enqueue("first"));
            assertFalse(dispatcher.enqueue("second"));
        }
    }

    @Test
    void dropOldestRetainsNewestItemWhenCapacityIsFull() throws Exception {
        try (MessageBatchDispatcher<String> dispatcher = new MessageBatchDispatcher<>(1, 1, 0,
                OverflowStrategy.DROP_OLDEST)) {
            assertTrue(dispatcher.enqueue("old"));
            assertTrue(dispatcher.enqueue("new"));
            CountDownLatch delivered = new CountDownLatch(1);
            AtomicReference<List<String>> received = new AtomicReference<>();
            dispatcher.addListener(batch -> {
                received.set(batch);
                delivered.countDown();
            });
            dispatcher.start();
            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("new"), received.get());
        }
    }

    @Test
    void flushesPartialBatchAfterIntervalAndIsolatesFailingListener() throws Exception {
        try (MessageBatchDispatcher<String> dispatcher = new MessageBatchDispatcher<>(4, 3, 20,
                OverflowStrategy.BLOCK)) {
            CountDownLatch delivered = new CountDownLatch(1);
            AtomicReference<List<String>> received = new AtomicReference<>();
            dispatcher.addListener(batch -> { throw new IllegalStateException("listener failure"); });
            dispatcher.addListener(batch -> {
                received.set(batch);
                delivered.countDown();
            });
            dispatcher.start();
            assertTrue(dispatcher.enqueue("one"));
            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("one"), received.get());
            assertThrows(UnsupportedOperationException.class, () -> received.get().add("unexpected"));
        }
    }

}
