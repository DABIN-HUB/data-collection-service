package com.wangbin.collector.core.report.lifecycle;

import com.wangbin.collector.core.cloud.model.CloudDeviceIdentity;
import com.wangbin.collector.core.cloud.protocol.alink.lifecycle.AlinkLifecycleCodec;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.springframework.stereotype.Component;

/**
 * MQTT 云设备生命周期发布器。
 */
@Slf4j
@Component
public class MqttCloudDeviceLifecyclePublisher {

    private final AlinkLifecycleCodec lifecycleCodec;

    public MqttCloudDeviceLifecyclePublisher(AlinkLifecycleCodec lifecycleCodec) {
        this.lifecycleCodec = lifecycleCodec;
    }

    public boolean publishGatewayOnline(MqttAsyncClient client,
                                        CloudDeviceIdentity gatewayIdentity,
                                        int qos,
                                        long timeoutMs) {
        try {
            return publish(client, lifecycleCodec.encodeGatewayOnline(gatewayIdentity), qos, timeoutMs, "上线");
        } catch (Exception e) {
            log.warn("构建 MQTT 生命周期上线消息失败：identity={} err={}", gatewayIdentity, e.getMessage(), e);
            return false;
        }
    }

    public boolean publishGatewayOffline(MqttAsyncClient client,
                                         CloudDeviceIdentity gatewayIdentity,
                                         int qos,
                                         long timeoutMs) {
        try {
            return publish(client, lifecycleCodec.encodeGatewayOffline(gatewayIdentity), qos, timeoutMs, "下线");
        } catch (Exception e) {
            log.warn("构建 MQTT 生命周期下线消息失败：identity={} err={}", gatewayIdentity, e.getMessage(), e);
            return false;
        }
    }

    private boolean publish(MqttAsyncClient client,
                            AlinkLifecycleCodec.LifecycleMessage message,
                            int qos,
                            long timeoutMs,
                            String action) {
        if (client == null || !client.isConnected()) {
            log.warn("跳过 MQTT 生命周期{}上报，客户端未连接：topic={}", action, message.topic());
            return false;
        }
        try {
            MqttMessage mqttMessage = new MqttMessage(message.payload());
            mqttMessage.setQos(Math.max(0, Math.min(1, qos)));
            mqttMessage.setRetained(false);
            IMqttToken token = client.publish(message.topic(), mqttMessage);
            token.waitForCompletion(Math.max(1000L, timeoutMs));
            log.info("MQTT 生命周期{}上报完成：topic={}", action, message.topic());
            return true;
        } catch (Exception e) {
            log.warn("MQTT 生命周期{}上报失败：topic={} err={}", action, message.topic(), e.getMessage(), e);
            return false;
        }
    }
}
