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
    static final ArchRule api_controller_should_not_be_referenced_by_core_or_common =
            noClasses().that().resideInAnyPackage(
                            "com.wangbin.collector.core..",
                            "com.wangbin.collector.common..")
                    .should().dependOnClassesThat().resideInAPackage("com.wangbin.collector.api.controller..");
}
