package com.wangbin.collector.core.connection.adapter;

import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetCovNotification;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyMultipleRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyMultipleResponse;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyResponse;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetRemoteDevice;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetSubscribeCovPropertyRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetSubscribeCovRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetWritePropertyRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetWritePropertyMultipleRequest;

import java.util.function.Consumer;

public interface BacnetConnectionAdapter {

    void setCovNotificationListener(Consumer<BacnetCovNotification> listener);

    BacnetReadPropertyResponse readProperty(BacnetReadPropertyRequest request, long timeoutMs) throws Exception;

    BacnetReadPropertyMultipleResponse readPropertyMultiple(BacnetReadPropertyMultipleRequest request,
                                                            long timeoutMs) throws Exception;

    void writeProperty(BacnetWritePropertyRequest request, long timeoutMs) throws Exception;

    default void writePropertyMultiple(BacnetWritePropertyMultipleRequest request, long timeoutMs) throws Exception {
        throw new UnsupportedOperationException("BACnet WritePropertyMultiple is not supported");
    }

    void subscribeCov(BacnetSubscribeCovRequest request, long timeoutMs) throws Exception;

    void subscribeCovProperty(BacnetSubscribeCovPropertyRequest request, long timeoutMs) throws Exception;

    default void acknowledgeConfirmedCovNotification(int invokeId) throws Exception {
    }

    BacnetRemoteDevice getRemoteDevice();

    default void setReconnectListener(Runnable listener) {
    }

    boolean isConnected();

    default String getTransportName() { return "UNKNOWN"; }
    default long getRequestRetryCount() { return 0L; }
    default long getRequestTimeoutCount() { return 0L; }
    default long getInvokeIdMismatchCount() { return 0L; }
    default long getCovNotificationCount() { return 0L; }
    default long getSegmentedResponseCount() { return 0L; }
    default boolean isForeignDeviceRegistrationActive() { return false; }
    default long getForeignDeviceRegistrationCount() { return 0L; }
    default long getForeignDeviceRenewCount() { return 0L; }
    default long getForeignDeviceRenewFailureCount() { return 0L; }
    default long getForeignDeviceLeaseExpiresAt() { return 0L; }
    default long getTokenReceiveCount() { return 0L; }
    default long getTokenPassCount() { return 0L; }
    default long getPollForMasterCount() { return 0L; }
    default long getReplyToPollCount() { return 0L; }
    default long getFrameErrorCount() { return 0L; }
    default long getCrcErrorCount() { return 0L; }
}
