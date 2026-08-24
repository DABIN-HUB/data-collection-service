package com.wangbin.collector.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 基础包依赖方向规则，仅锁定当前阶段已经治理完成的反向依赖。
 */
@AnalyzeClasses(packages = "com.wangbin.collector", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureDependencyTest {

    @ArchTest
    static final ArchRule common_should_not_depend_on_upper_layers =
            noClasses().that().resideInAPackage("com.wangbin.collector.common..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.wangbin.collector.core..",
                            "com.wangbin.collector.api..",
                            "com.wangbin.collector.monitor..",
                            "com.wangbin.collector.storage..");

    @ArchTest
    static final ArchRule core_should_not_depend_on_api =
            noClasses().that().resideInAPackage("com.wangbin.collector.core..")
                    .should().dependOnClassesThat().resideInAPackage("com.wangbin.collector.api..");

    @ArchTest
    static final ArchRule core_should_not_depend_on_monitor =
            noClasses().that().resideInAPackage("com.wangbin.collector.core..")
                    .should().dependOnClassesThat().resideInAPackage("com.wangbin.collector.monitor..");

    @ArchTest
    static final ArchRule core_should_not_depend_on_storage =
            noClasses().that().resideInAPackage("com.wangbin.collector.core..")
                    .should().dependOnClassesThat().resideInAPackage("com.wangbin.collector.storage..");

    @ArchTest
    static final ArchRule api_controller_should_not_be_referenced_by_core_or_common =
            noClasses().that().resideInAnyPackage(
                            "com.wangbin.collector.core..",
                            "com.wangbin.collector.common..")
                    .should().dependOnClassesThat().resideInAPackage("com.wangbin.collector.api.controller..");

    @ArchTest
    static final ArchRule api_should_not_depend_on_protocol_implementations =
            noClasses().that().resideInAPackage("com.wangbin.collector.api..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.wangbin.collector.core.collector.protocol.ads..",
                            "com.wangbin.collector.core.collector.protocol.bacnet..",
                            "com.wangbin.collector.core.collector.protocol.coap..",
                            "com.wangbin.collector.core.collector.protocol.custom..",
                            "com.wangbin.collector.core.collector.protocol.dlt645..",
                            "com.wangbin.collector.core.collector.protocol.ethernetip..",
                            "com.wangbin.collector.core.collector.protocol.fins..",
                            "com.wangbin.collector.core.collector.protocol.http..",
                            "com.wangbin.collector.core.collector.protocol.iec..",
                            "com.wangbin.collector.core.collector.protocol.iec101..",
                            "com.wangbin.collector.core.collector.protocol.knx..",
                            "com.wangbin.collector.core.collector.protocol.mc..",
                            "com.wangbin.collector.core.collector.protocol.modbus..",
                            "com.wangbin.collector.core.collector.protocol.mqtt..",
                            "com.wangbin.collector.core.collector.protocol.opc..",
                            "com.wangbin.collector.core.collector.protocol.plc4x..",
                            "com.wangbin.collector.core.collector.protocol.s7..",
                            "com.wangbin.collector.core.collector.protocol.snmp..",
                            "com.wangbin.collector.core.collector.protocol.websocket..");

    @ArchTest
    static final ArchRule protocol_implementations_should_not_depend_on_storage_monitor_or_cloud =
            noClasses().that().resideInAnyPackage(
                            "com.wangbin.collector.core.collector.protocol..",
                            "com.wangbin.collector.core.connection.adapter..",
                            "com.wangbin.collector.core.connection.factory.provider..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.wangbin.collector.storage..",
                            "com.wangbin.collector.monitor..",
                            "com.wangbin.collector.core.cloud..",
                            "com.wangbin.collector.core.report..");
}
