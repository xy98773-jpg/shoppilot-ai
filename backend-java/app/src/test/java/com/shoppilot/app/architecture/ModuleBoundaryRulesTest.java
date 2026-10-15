package com.shoppilot.app.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * M1-T02 占位架构守护：仅断言骨架期的两条结构性不变量。
 *
 * <p>F-40 已裁定 {@code common} / {@code audit} 为通用可依赖叶子模块（豁免于逐模块白名单）。
 * 各业务模块的「逐模块白名单」依赖断言将在 M2 业务类落地后补入本测试。
 */
@AnalyzeClasses(packages = "com.shoppilot", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryRulesTest {

    /**
     * 规则 1：任何业务模块不得依赖 app（DAG 无环，违者即环）。
     * app 是组合根 / DAG 顶点，业务模块依赖 app 会破坏依赖方向并形成环。
     *
     * <p>allowEmptyShould(true)：骨架期尚无业务类，该规则本就无类可检；
     * 待 M2 业务类落地后，规则自动对越界依赖生效，无需改动。
     */
    @ArchTest
    static final ArchRule businessModulesMustNotDependOnApp = noClasses()
            .that().resideOutsideOfPackage("com.shoppilot.app..")
            .should().dependOnClassesThat().resideInAPackage("com.shoppilot.app..")
            .because("app 是组合根/DAG 顶点，业务模块依赖 app 会破坏依赖方向并形成环")
            .allowEmptyShould(true);
}
