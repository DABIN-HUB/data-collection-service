package com.wangbin.collector.core.config.protocol;

import com.wangbin.collector.core.collector.protocol.fins.OmronFinsCollector;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * OMRON FINS 协议元数据提供者。
 */
@Component
@Order(60)
public class OmronProtocolDescriptorProvider implements ProtocolDescriptorProvider {

    @Override
    public void register(ProtocolDescriptorRegistry registry) {
        registry.registerPrimary(registry.descriptor("OMRON_FINS", "OMRON FINS",
                "Self-owned OMRON FINS/UDP collector for polling read/write on common PLC memory areas.",
                List.of("FINS", "OMRONFINS"), OmronFinsCollector.class, "OMRON_FINS", 9600,
                ProtocolAddressingMode.MIXED,
                true, true, false,
                List.of("DM:100", "DM:100.3", "CIO:0.1", "WR:20", "HR:50", "EM0:100", "DM:200#8"),
                registry.fields(
                        registry.field("host", "string", "Device host", true, "127.0.0.1", null, "connection",
                                "OMRON PLC IP address for FINS/UDP communication."),
                        registry.field("port", "number", "Port", false, "9600", null, "connection",
                                "FINS/UDP destination port. Leave empty to use the default 9600."),
                        registry.field("plcNode", "number", "PLC node", true, "1", null, "protocol",
                                "Destination node number on the PLC side."),
                        registry.field("localNode", "number", "Local node", true, "10", null, "protocol",
                                "Source node number used by the collector host."),
                        registry.field("plcUnit", "number", "PLC unit", false, "0", null, "protocol",
                                "Destination unit number. CPU unit commonly uses 0."),
                        registry.field("localUnit", "number", "Local unit", false, "0", null, "protocol",
                                "Source unit number used in the FINS header."),
                        registry.field("plcNetwork", "number", "PLC network", false, "0", null, "protocol",
                                "Destination network number. Direct Ethernet access commonly uses 0."),
                        registry.field("localNetwork", "number", "Local network", false, "0", null, "protocol",
                                "Source network number used in the FINS header."),
                        registry.field("serviceIdSeed", "number", "Service ID seed", false, "1", null, "advanced",
                                "Initial FINS SID value used for request sequencing."),
                        registry.field("batchReadEnabled", "boolean", "Enable batch read", false, "true",
                                List.of("true", "false"), "advanced",
                                "Enable protocol-level contiguous block read merging inside the collector."),
                        registry.field("maxWordsPerRequest", "number", "Max words per request", false, "120", null, "advanced",
                                "Collector-side limit for one word-unit FINS read/write request."),
                        registry.field("maxBitsPerRequest", "number", "Max bits per request", false, "256", null, "advanced",
                                "Collector-side limit for one bit-unit FINS read/write request."),
                        registry.field("byteOrder", "select", "Byte order", false, "BIG_ENDIAN",
                                List.of("BIG_ENDIAN", "LITTLE_ENDIAN"), "advanced",
                                "Default byte order used for multi-byte numeric decoding when the point does not override it."),
                        registry.field("wordOrder", "select", "Word order", false, "BIG_ENDIAN",
                                List.of("BIG_ENDIAN", "LITTLE_ENDIAN"), "advanced",
                                "Default word order used for 32-bit and 64-bit values when the point does not override it."),
                        registry.field("readTimeout", "number", "Read timeout (ms)", false, "5000", null, "advanced",
                                "UDP receive timeout while waiting for one FINS response."),
                        registry.field("timeout", "number", "Protocol timeout (ms)", false, "5000", null, "advanced",
                                "Fallback timeout used when readTimeout is empty.")))
                .withPointFields(finsPointFields(registry)));
    }

    private List<ProtocolFieldConfig> finsPointFields(ProtocolDescriptorRegistry registry) {
        return List.of(
                registry.pointField("additionalConfig.bitIndex", "number", "Bit index", false, "",
                        Collections.emptyList(), "Optional bit offset override. You can use either address=DM:100.3 or address=DM:100 with additionalConfig.bitIndex=3. Only BOOL points are supported.", "dataType=BOOLEAN"),
                registry.pointField("additionalConfig.stringLength", "number", "String length", false, "",
                        Collections.emptyList(), "Required when dataType=STRING and the address does not already use #length.", "dataType=STRING"),
                registry.pointField("additionalConfig.arraySize", "number", "Array size", false, "",
                        Collections.emptyList(), "Optional one-dimensional array length for word-based numeric arrays. Bit arrays are not supported in P0.", null),
                registry.pointField("additionalConfig.byteOrder", "select", "Byte order", false, "",
                        List.of("BIG_ENDIAN", "LITTLE_ENDIAN"), "Optional per-point byte-order override for multi-byte numeric values.", null),
                registry.pointField("additionalConfig.wordOrder", "select", "Word order", false, "",
                        List.of("BIG_ENDIAN", "LITTLE_ENDIAN"), "Optional per-point word-order override for 32-bit and 64-bit values.", null)
        );
    }
}
