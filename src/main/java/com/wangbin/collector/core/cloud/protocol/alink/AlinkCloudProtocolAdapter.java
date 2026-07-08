package com.wangbin.collector.core.cloud.protocol.alink;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.constant.MessageConstant;
import com.wangbin.collector.core.cloud.config.CloudPayloadOptions;
import com.wangbin.collector.core.cloud.model.CloudDeviceIdentity;
import com.wangbin.collector.core.cloud.protocol.CloudProtocolAdapter;
import com.wangbin.collector.core.cloud.protocol.CloudProtocolMessage;
import com.wangbin.collector.core.cloud.protocol.alink.codec.AlinkMessageEnvelope;
import com.wangbin.collector.core.cloud.protocol.alink.codec.AlinkPayloadDecoder;
import com.wangbin.collector.core.cloud.protocol.alink.codec.AlinkPayloadEncoder;
import com.wangbin.collector.core.cloud.protocol.alink.topic.AlinkTopicBuilder;
import com.wangbin.collector.core.cloud.protocol.alink.topic.AlinkTopicParser;
import com.wangbin.collector.core.report.model.ReportConfig;
import com.wangbin.collector.core.report.model.ReportData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.List;

/**
 * 阿里云 Alink 协议适配器。
 */
@Component
public class AlinkCloudProtocolAdapter implements CloudProtocolAdapter {

    public static final String PROVIDER = "alink";
    public static final List<String> DOWNLINK_TOPIC_PATHS = List.of(
            AlinkMethod.PROPERTY_SET.path(),
            AlinkMethod.SERVICE_INVOKE.path(),
            AlinkMethod.CONFIG_PUSH.path(),
            AlinkMethod.OTA_UPGRADE.path(),
            AlinkMethod.TOPO_CHANGE.path(),
            AlinkMethod.TOPO_ADD.path(),
            AlinkMethod.TOPO_DELETE.path(),
            AlinkMethod.TOPO_GET.path(),
            AlinkMethod.AUTH_REGISTER_SUB.path()
    );

    private final AlinkTopicBuilder topicBuilder;
    private final AlinkPayloadEncoder payloadEncoder;
    private final AlinkPayloadDecoder payloadDecoder;

    @Autowired
    public AlinkCloudProtocolAdapter(AlinkTopicBuilder topicBuilder,
                                     AlinkPayloadEncoder payloadEncoder,
                                     AlinkPayloadDecoder payloadDecoder) {
        this.topicBuilder = topicBuilder;
        this.payloadEncoder = payloadEncoder;
        this.payloadDecoder = payloadDecoder;
    }

    public static AlinkCloudProtocolAdapter standalone(ObjectMapper objectMapper) {
        return new AlinkCloudProtocolAdapter(
                new AlinkTopicBuilder(),
                new AlinkPayloadEncoder(objectMapper),
                new AlinkPayloadDecoder(objectMapper, new AlinkTopicParser()));
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public List<String> aliases() {
        return List.of(PROVIDER, "aliyun", "ali");
    }

    @Override
    public String buildPublishTopic(ReportData data, ReportConfig config) {
        CloudDeviceIdentity identity = resolveCloudIdentity(data, config);
        return topicBuilder.build(identity, resolveMethod(data));
    }

    @Override
    public byte[] encodeReportData(ReportData data, CloudPayloadOptions options) {
        return payloadEncoder.encodeReportData(data, options);
    }

    @Override
    public CloudProtocolMessage decode(String topic, byte[] payload) throws IOException {
        AlinkMessageEnvelope envelope = payloadDecoder.decode(topic, payload);
        return new CloudProtocolMessage(
                envelope.id(),
                envelope.version(),
                envelope.method() != null ? envelope.method().method() : null,
                envelope.identity(),
                envelope.payload(),
                envelope.params());
    }

    @Override
    public List<String> downlinkTopicPaths() {
        return DOWNLINK_TOPIC_PATHS;
    }

    private CloudDeviceIdentity resolveCloudIdentity(ReportData data, ReportConfig config) {
        String productKey = firstText(
                metadataText(data, "productKey"),
                configText(config, "gatewayProductKey"),
                configText(config, "defaultProductKey"));
        String deviceName = firstText(
                data != null ? data.getDeviceId() : null,
                metadataText(data, "deviceName"),
                configText(config, "gatewayDeviceName"),
                config != null ? config.getTargetId() : null);
        CloudDeviceIdentity identity = CloudDeviceIdentity.of(productKey, deviceName);
        if (!identity.valid()) {
            throw new IllegalStateException("云平台 MQTT 上报缺少 productKey 或 deviceName");
        }
        return identity;
    }

    private String resolveMethod(ReportData data) {
        String method = data != null ? data.getMethod() : null;
        return StringUtils.hasText(method) ? method : MessageConstant.MESSAGE_TYPE_PROPERTY_POST;
    }

    private String metadataText(ReportData data, String key) {
        if (data == null || data.getMetadata() == null || key == null) {
            return null;
        }
        Object value = data.getMetadata().get(key);
        return value == null ? null : String.valueOf(value);
    }

    private String configText(ReportConfig config, String key) {
        if (config == null || key == null) {
            return null;
        }
        String value = config.getStringParam(key);
        if (StringUtils.hasText(value)) {
            return value;
        }
        Object raw = config.getParams() != null ? config.getParams().get(key) : null;
        return raw == null ? null : String.valueOf(raw);
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }
}
