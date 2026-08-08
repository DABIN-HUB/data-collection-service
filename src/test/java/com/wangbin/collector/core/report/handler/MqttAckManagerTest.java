package com.wangbin.collector.core.report.handler;

import com.wangbin.collector.core.cloud.config.CloudAckCommitMode;
import com.wangbin.collector.core.cloud.config.CloudAckMode;
import com.wangbin.collector.core.cloud.config.CloudAckOptions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MqttAckManagerTest {

    @Test
    void completedAckShouldRemovePendingTicketAndWakeAwaiter() throws Exception {
        AckHarness ack = new AckHarness();
        Object registration = ack.register("msg-ok", options(1000L));

        ack.complete("msg-ok", ack.received("msg-ok", 0, "ok"));
        Object result = ack.await(registration, 1000L);

        assertEquals(0, ack.pendingSize());
        assertEquals(0, ack.intField(result, "code"));
    }

    @Test
    void timeoutShouldRemovePendingTicket() throws Exception {
        AckHarness ack = new AckHarness();
        Object registration = ack.register("msg-timeout", options(1000L));

        Object result = ack.await(registration, 1L);

        assertEquals(0, ack.pendingSize());
        assertTrue(ack.booleanField(result, "timeout"));
    }

    @Test
    void cancelShouldRemovePendingTicket() throws Exception {
        AckHarness ack = new AckHarness();
        Object registration = ack.register("msg-cancel", options(1000L));

        ack.cancel(registration);

        assertEquals(0, ack.pendingSize());
    }

    @Test
    void repeatedAckCompletionShouldNotLeakPendingTickets() throws Exception {
        AckHarness ack = new AckHarness();

        for (int i = 0; i < 300; i++) {
            String messageId = "msg-loop-" + i;
            Object registration = ack.register(messageId, options(1000L));
            ack.complete(messageId, ack.received(messageId, 0, "ok"));
            Object result = ack.await(registration, 1000L);
            assertEquals(0, ack.intField(result, "code"));
        }

        assertEquals(0, ack.pendingSize());
    }

    private static CloudAckOptions options(long timeoutMs) {
        return new CloudAckOptions(
                CloudAckMode.ASYNC,
                timeoutMs,
                100,
                100,
                CloudAckCommitMode.ACK_SUCCESS);
    }

    private static final class AckHarness {
        private final Object manager;
        private final Method register;
        private final Method await;
        private final Method complete;
        private final Method cancel;
        private final Method received;
        private final Field pendingAcks;

        private AckHarness() throws Exception {
            Class<?> managerType = Class.forName(MqttReportHandler.class.getName() + "$AckManager");
            Constructor<?> constructor = managerType.getDeclaredConstructor();
            constructor.setAccessible(true);
            manager = constructor.newInstance();
            register = accessibleMethod(managerType, "register", String.class, CloudAckOptions.class);
            Class<?> registrationType = Class.forName(MqttReportHandler.class.getName() + "$AckManager$AckRegistration");
            await = accessibleMethod(managerType, "await", registrationType, long.class);
            Class<?> ackMessageType = Class.forName(MqttReportHandler.class.getName() + "$AckMessage");
            complete = accessibleMethod(managerType, "complete", String.class, ackMessageType);
            cancel = accessibleMethod(managerType, "cancel", registrationType);
            received = accessibleMethod(ackMessageType, "received", String.class, int.class, String.class);
            pendingAcks = managerType.getDeclaredField("pendingAcks");
            pendingAcks.setAccessible(true);
        }

        private Object register(String messageId, CloudAckOptions options) throws Exception {
            return register.invoke(manager, messageId, options);
        }

        private Object await(Object registration, long timeoutMs) throws Exception {
            return await.invoke(manager, registration, timeoutMs);
        }

        private void complete(String messageId, Object ackMessage) throws Exception {
            complete.invoke(manager, messageId, ackMessage);
        }

        private void cancel(Object registration) throws Exception {
            cancel.invoke(manager, registration);
        }

        private Object received(String messageId, int code, String message) throws Exception {
            return received.invoke(null, messageId, code, message);
        }

        @SuppressWarnings("unchecked")
        private int pendingSize() throws Exception {
            return ((Map<String, ?>) pendingAcks.get(manager)).size();
        }

        private int intField(Object target, String name) throws Exception {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(target);
        }

        private boolean booleanField(Object target, String name) throws Exception {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.getBoolean(target);
        }

        private static Method accessibleMethod(Class<?> type, String name, Class<?>... parameterTypes) throws Exception {
            Method method = type.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        }
    }
}
