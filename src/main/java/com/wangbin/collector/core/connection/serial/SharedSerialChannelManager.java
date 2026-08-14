package com.wangbin.collector.core.connection.serial;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 共享串口管理器，保证一个物理端口只由一个协议总线打开。
 */
@Component
public class SharedSerialChannelManager {

    private final Map<String, SharedEntry> entries = new HashMap<>();
    private final SerialChannelFactory channelFactory;

    /**
     * 创建当前组件实例。
     */
    public SharedSerialChannelManager() {
        this(JSerialCommSerialChannel::new);
    }

    /**
     * 创建当前组件实例。
     */
    public SharedSerialChannelManager(SerialChannelFactory channelFactory) {
        this.channelFactory = channelFactory;
    }

    /**
     * 执行当前业务逻辑。
     */
    public synchronized Lease acquire(SerialEndpoint endpoint, String owner) throws Exception {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("串口占用方不能为空");
        }
        String key = endpoint.physicalPortKey();
        SharedEntry existing = entries.get(key);
        if (existing != null) {
            validateSharedEntry(existing, endpoint, owner);
            existing.references++;
            return new Lease(this, key, existing);
        }
        SerialChannel channel = channelFactory.create(endpoint);
        channel.open();
        SharedEntry created = new SharedEntry(endpoint, owner, channel);
        created.references = 1;
        entries.put(key, created);
        return new Lease(this, key, created);
    }

    /**
     * 执行当前业务逻辑。
     */
    public synchronized int activePortCount() {
        return entries.size();
    }

    /**
     * 校验业务条件和参数边界。
     */
    private void validateSharedEntry(SharedEntry existing, SerialEndpoint endpoint, String owner) {
        if (!existing.endpoint.equals(endpoint)) {
            throw new IllegalStateException("同一串口存在冲突的通信参数: " + endpoint.portName());
        }
        if (!existing.owner.equals(owner)) {
            throw new IllegalStateException("串口已被其他协议占用: " + endpoint.portName());
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private synchronized void release(String key, SharedEntry expected) throws Exception {
        SharedEntry current = entries.get(key);
        if (current == null || current != expected) {
            return;
        }
        current.references--;
        if (current.references <= 0) {
            entries.remove(key);
            current.channel.close();
        }
    }

    /**
     * 定义当前模块的业务契约。
     */
    @FunctionalInterface
    public interface SerialOperation<T> {

        /**
         * 处理当前业务流程。
         */
        T execute(SerialChannel channel) throws Exception;
    }

    /**
     * 定义当前模块的业务组件。
     */
    public static final class Lease implements AutoCloseable {

        private final SharedSerialChannelManager manager;
        private final String key;
        private final SharedEntry entry;
        private boolean closed;

        /**
         * 创建当前组件实例。
         */
        private Lease(SharedSerialChannelManager manager, String key, SharedEntry entry) {
            this.manager = manager;
            this.key = key;
            this.entry = entry;
        }

        /**
         * 处理当前业务流程。
         */
        public <T> T execute(SerialOperation<T> operation) throws Exception {
            if (closed) {
                throw new IllegalStateException("串口租约已经释放");
            }
            entry.lock.lockInterruptibly();
            try {
                return operation.execute(entry.channel);
            } finally {
                entry.lock.unlock();
            }
        }

        public boolean isOpen() {
            return !closed && entry.channel.isOpen();
        }

        /**
         * 执行当前业务逻辑。
         */
        @Override
        public synchronized void close() throws Exception {
            if (closed) {
                return;
            }
            closed = true;
            manager.release(key, entry);
        }
    }

    /**
     * 定义当前模块的业务组件。
     */
    private static final class SharedEntry {

        private final SerialEndpoint endpoint;
        private final String owner;
        private final SerialChannel channel;
        private final ReentrantLock lock = new ReentrantLock(true);
        private int references;

        /**
         * 创建当前组件实例。
         */
        private SharedEntry(SerialEndpoint endpoint, String owner, SerialChannel channel) {
            this.endpoint = endpoint;
            this.owner = owner;
            this.channel = channel;
        }
    }
}
