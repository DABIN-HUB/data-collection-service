package com.wangbin.collector.core.collector.scheduler;

/**
 * 设备调度信息
 */
class DeviceScheduleInfo {

    private final String deviceId;
    private final long startTime;
    private final long generation;
    private volatile boolean running;

    DeviceScheduleInfo(String deviceId, long generation, boolean running) {
        this.deviceId = deviceId;
        this.generation = generation;
        this.running = running;
        this.startTime = System.currentTimeMillis();
    }

    String getDeviceId() {
        return deviceId;
    }

    long getStartTime() {
        return startTime;
    }

    long getGeneration() {
        return generation;
    }

    boolean isRunning() {
        return running;
    }

    void setRunning(boolean running) {
        this.running = running;
    }
}
