package com.wangbin.collector.core.config.protocol;

import com.wangbin.collector.core.collector.protocol.mc.McCollector;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 三菱 MC 协议元数据提供者。
 */
@Component
@Order(50)
public class MitsubishiProtocolDescriptorProvider implements ProtocolDescriptorProvider {

    @Override
    public void register(ProtocolDescriptorRegistry registry) {
        registry.registerPrimary(registry.descriptor("MITSUBISHI_MC", "Mitsubishi MC",
                "Self-owned Mitsubishi MC 3E Binary over TCP collector for polling read/write.",
                List.of("MC", "MELSEC_MC"), McCollector.class, "MITSUBISHI_MC", 5000,
                ProtocolAddressingMode.MIXED,
                true, true, false,
                List.of("M0", "X1A", "Y2F", "D100", "D100[4]", "R200", "W300", "ZR1000"),
                registry.fields(
                        registry.field("host", "string", "Device host", true, "127.0.0.1", null, "connection",
                                "Mitsubishi PLC IP address for MC over TCP."),
                        registry.field("port", "number", "Port", false, "5000", null, "connection",
                                "MC TCP port. Leave empty to use the default 5000."),
                        registry.field("networkNo", "number", "Network No", false, "0", null, "protocol",
                                "3E frame network number, usually 0 for a directly connected CPU."),
                        registry.field("pcNo", "number", "PC No", false, "255", null, "protocol",
                                "3E frame PC number. The common default is 255."),
                        registry.field("ioNo", "number", "I/O No", false, "1023", null, "protocol",
                                "3E frame destination I/O number. The common Ethernet default is 1023."),
                        registry.field("stationNo", "number", "Station No", false, "0", null, "protocol",
                                "3E frame destination station number."),
                        registry.field("monitoringTimer", "number", "Monitoring timer", false, "16", null, "advanced",
                                "MC monitoring timer in protocol units used inside each 3E request frame."),
                        registry.field("frameType", "select", "Frame type", false, "3E_BINARY",
                                List.of("3E_BINARY", "3E_ASCII", "4E_BINARY"), "advanced",
                                "MC frame variant selector. The current stable path is 3E_BINARY. Other variants are reserved for staged rollout."),
                        registry.field("randomReadEnabled", "boolean", "Enable random read", false, "false",
                                List.of("true", "false"), "advanced",
                                "Enable MC random-read strategy for sparse scalar word points. Continuous blocks still prefer batch read."),
                        registry.field("maxRandomReadPoints", "number", "Max random-read points", false, "8", null, "advanced",
                                "Upper bound for one random-read request. Requests beyond this size fall back to normal plan-based reads."),
                        registry.field("randomWriteEnabled", "boolean", "Enable random write", false, "false",
                                List.of("true", "false"), "advanced",
                                "Enable MC random-write strategy for sparse scalar word points. Continuous blocks still prefer batch writes."),
                        registry.field("maxRandomWritePoints", "number", "Max random-write points", false, "8", null, "advanced",
                                "Upper bound for one random-write request. Requests beyond this size fall back to normal plan-based writes."),
                        registry.field("maxWordsPerRequest", "number", "Max words per request", false, "120", null, "advanced",
                                "Collector-side guard rail for one word-unit batch request."),
                        registry.field("maxBitsPerRequest", "number", "Max bits per request", false, "256", null, "advanced",
                                "Collector-side guard rail for one bit-unit batch request."),
                        registry.field("readTimeout", "number", "Read timeout (ms)", false, "5000", null, "advanced",
                                "Socket read timeout used while waiting for one MC response frame."),
                        registry.field("timeout", "number", "Protocol timeout (ms)", false, "5000", null, "advanced",
                                "Fallback timeout used when readTimeout is empty.")))
                .withDriverPrimarySchema("MC driver type", mcDriverDataTypes(), mcPointFields(registry)));
    }

    private List<String> mcDriverDataTypes() {
        return List.of("BOOL", "INT16", "UINT16", "INT32", "UINT32", "FLOAT32", "FLOAT64", "STRING");
    }

    private List<ProtocolFieldConfig> mcPointFields(ProtocolDescriptorRegistry registry) {
        return List.of(
                registry.pointField("additionalConfig.bitIndex", "number", "Bit index", false, "",
                        Collections.emptyList(), "Optional bit offset inside one word device. You can use either D100.3 syntax or address=D100 with additionalConfig.bitIndex=3. Only BOOL points are supported.", "dataType=BOOLEAN/driverDataType=BOOL"),
                registry.pointField("additionalConfig.stringLength", "number", "String length", false, "",
                        Collections.emptyList(), "Required when driverDataType=STRING. The value is the string character length, and the collector allocates the corresponding MC word span.", "driverDataType=STRING"),
                registry.pointField("additionalConfig.arraySize", "number", "Array size", false, "",
                        Collections.emptyList(), "Optional one-dimensional array length when the address does not already include [n]. BOOL arrays use bit-unit batches, numeric arrays use word-unit batches.", null)
        );
    }
}
