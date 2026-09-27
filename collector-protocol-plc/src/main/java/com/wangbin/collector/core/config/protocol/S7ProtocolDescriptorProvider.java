package com.wangbin.collector.core.config.protocol;

import com.wangbin.collector.core.collector.protocol.s7.S7Collector;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 西门子 S7 协议元数据提供者。
 */
@Component
@Order(20)
public class S7ProtocolDescriptorProvider implements ProtocolDescriptorProvider {

    /**
     * 注册西门子 S7 协议字段、能力和地址示例。
     *
     * @param registry 协议元数据注册表
     */
    @Override
    public void register(ProtocolDescriptorRegistry registry) {
        registry.registerPrimary(registry.descriptor("SIEMENS_S7", "西门子 S7",
                "基于 PLC4X 的西门子 S7 读写采集器。",
                List.of("S7"), S7Collector.class, "SIEMENS_S7", 102, ProtocolAddressingMode.MIXED,
                ProtocolCapabilityState.SUPPORTED,
                ProtocolCapabilityState.SUPPORTED,
                ProtocolCapabilityState.RUNTIME_DEPENDENT,
                ProtocolCapabilityState.UNSUPPORTED,
                List.of("DB1.DBX0.0", "DB1.DBW0", "DB1.DBD4", "%DB1:4:REAL", "%DB1.DBX0.0:BOOL", "I0.0", "Q0.0", "M10.0"),
                registry.fields(
                        registry.conditional("host", "string", "设备地址", false, "127.0.0.1", null,
                                "connection", "plc4xConnectionString=empty",
                                "未配置 plc4xConnectionString 时必填（也可使用设备 ipAddress）；显式连接串优先，普通路由字段不再决定连接。"),
                        registry.field("port", "number", "端口", false, "102", null, "connection",
                                "S7 TCP 端口，留空时使用默认端口 102。"),
                        registry.field("rack", "number", "机架号", false, "0", null, "protocol",
                                "生成连接串时使用，取值 0～7；显式连接串下忽略。"),
                        registry.field("slot", "number", "槽号", false, "1", null, "protocol",
                                "生成连接串时使用，取值 0～31；显式连接串下忽略。"),
                        registry.field("controllerType", "select", "控制器类型", false, "S7_1200",
                                List.of("S7_300", "S7_400", "S7_1200", "S7_1500", "LOGO"), "protocol",
                                "普通路由只支持列出的 PLC 系列（不接受 S7_200/ANY）；为兼容历史配置默认 S7_1200。S7-1200/1500 绝对地址需关闭 DB 优化访问。显式连接串下忽略。"),
                        registry.field("pduSize", "number", "PDU 大小", false, "1024", null, "advanced",
                                "PLC4X S7 协商 PDU 大小参数，取值 1～65535，建议保留默认 1024；显式连接串下忽略。"),
                        registry.field("maxFieldsPerRequest", "number", "单次最大字段数", false, "64", null, "advanced",
                                "采集侧批量上限，必须大于 0；不属于连接串，显式连接串下仍生效。"),
                        registry.field("subscriptionEnabled", "select", "启用订阅", false, "",
                                List.of("true", "false"), "advanced",
                                "当前 S7 采集器支持周期订阅，以及点位配置为订阅或事件模式时的 MODE/SYS/USR/ALM 事件订阅。"),
                        registry.field("localTsap", "number", "本地 TSAP", false, "", null, "advanced",
                                "需要显式 PG/OP 路由参数的现场可填写 PLC4X 本地 TSAP 覆盖值。"),
                        registry.field("remoteTsap", "number", "远端 TSAP", false, "", null, "advanced",
                                "需要显式 PG/OP 路由参数的现场可填写 PLC4X 远端 TSAP 覆盖值。"),
                        registry.field("localDeviceGroup", "select", "本地设备组", false, "",
                                List.of("PG_OR_PC", "OS", "OTHERS"), "advanced",
                                "使用 TSAP 路由时的 PLC4X 本地设备组覆盖值。"),
                        registry.field("remoteDeviceGroup", "select", "远端设备组", false, "",
                                List.of("PG_OR_PC", "OS", "OTHERS"), "advanced",
                                "使用 TSAP 路由时的 PLC4X 远端设备组覆盖值。"),
                        registry.field("ping", "boolean", "启用 PLC4X 心跳", false, "false",
                                List.of("true", "false"), "advanced",
                                "默认关闭。仅确需驱动心跳时启用；PLC4X read-timeout 仅在此项启用时作用于 Netty，重连仍由采集框架负责。显式连接串下忽略。"),
                        registry.field("pingTime", "number", "心跳间隔（秒）", false, "", null, "advanced",
                                "ping=true 时的 PLC4X 心跳间隔，单位为秒；显式连接串下忽略。"),
                        registry.field("retryTime", "number", "驱动重连间隔（秒）", false, "0", null, "advanced",
                                "仅允许 0；采集框架负责断线重连，禁止启用 PLC4X retry-time 的第二重连 owner。"),
                        registry.field("plc4xConnectionString", "string", "PLC4X 连接串", false, "", null, "advanced",
                                "完整 s7://host:port[?参数] 连接串；非空时是唯一连接来源，普通 host/port/controller/rack/slot/PDU/ping/retry 均不注入。不可设置 retry-time>0，连接信息不展示原始串。"),
                        registry.field("readTimeout", "number", "读取超时（毫秒）", false, "30000", null, "advanced",
                                "采集侧 Future 等待超时，单位毫秒且必须大于 0；普通连接 ping=true 时另按向上取整转换为 PLC4X read-timeout 秒数。此项不能覆盖驱动内部固定的请求超时。"),
                        registry.field("timeout", "number", "协议超时（毫秒）", false, "30000", null, "advanced",
                                "readTimeout 未设置时的兜底值，单位毫秒且必须大于 0。")))
                .withDriverPrimarySchema("S7 driver type", s7DriverDataTypes(), s7PointFields(registry)));
    }

    private List<String> s7DriverDataTypes() {
        return List.of(
                "BOOL", "SINT", "USINT", "INT", "UINT", "DINT", "UDINT", "LINT", "ULINT",
                "REAL", "LREAL", "CHAR", "WCHAR", "STRING", "WSTRING",
                "TIME", "LTIME", "DATE", "TIME_OF_DAY", "DATE_AND_TIME", "S5TIME");
    }

    private List<ProtocolFieldConfig> s7PointFields(ProtocolDescriptorRegistry registry) {
        return List.of(
                registry.pointField("additionalConfig.subscriptionMode", "select", "Subscription mode", false, "",
                        List.of("CYCLIC", "MODE", "SYS", "USR", "ALM"),
                        "Used when collectionMode=SUBSCRIPTION or EVENT. CYCLIC uses the point's absolute address. MODE/SYS/USR/ALM register S7 event subscriptions.", null),
                registry.pointField("additionalConfig.subscriptionAddress", "string", "Subscription address", false, "",
                        Collections.emptyList(),
                        "Optional PLC4X subscription address override. For MODE/SYS/USR/ALM it usually matches the mode token; leave empty to reuse the point address or selected mode.", null),
                registry.pointField("additionalConfig.stringLength", "number", "String length", false, "",
                        Collections.emptyList(),
                        "Used when driverDataType=STRING or WSTRING to declare the PLC string length.", "driverDataType=STRING/WSTRING"),
                registry.pointField("additionalConfig.arraySize", "number", "Array size", false, "",
                        Collections.emptyList(),
                        "Optional one-dimensional array length when the address does not already include [n]. Only full-array read/write is supported.", null)
        );
    }
}
