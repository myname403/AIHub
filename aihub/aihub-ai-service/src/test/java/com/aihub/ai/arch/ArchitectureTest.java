package com.aihub.ai.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 架构依赖卡口（★ 防止架构腐化的根本保障）。
 *
 * <p>把「分层解耦」从口头约定变成<b>构建期强制</b>：任何一次违规依赖都会让 CI 失败。
 * 规则与 03 号架构文档第 4 节保持一致（作用域：本服务内部）。
 */
class ArchitectureTest {

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.aihub.ai");

    /** 1) 领域层保持纯净：不依赖上层，也不依赖任何框架 */
    @Test
    void domainShouldBeFrameworkFree() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..aihub.ai.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..aihub.ai.application..",
                        "..aihub.ai.web..",
                        "..aihub.ai.infra..",
                        "org.springframework.ai..",
                        "com.baomidou..",
                        "org.springframework.data.redis..");
        rule.check(classes);
    }

    /** 2) 编排层只认接口：不得依赖基础设施与表现层 */
    @Test
    void applicationShouldNotDependOnInfraOrWeb() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..aihub.ai.application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..aihub.ai.infra..",
                        "..aihub.ai.web..");
        rule.check(classes);
    }

    /** 3) 表现层不得直接触碰 Spring AI 与持久化 / 基础设施类型 */
    @Test
    void webShouldNotTouchSpringAiOrInfra() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..aihub.ai.web..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.ai..",
                        "..aihub.ai.infra..");
        rule.check(classes);
    }

    /** 4) 基础设施各模块之间禁止横向依赖 */
    @Test
    void infraModulesShouldNotDependOnEachOther() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..aihub.ai.infra.ai..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..aihub.ai.infra.persistence..",
                        "..aihub.ai.infra.security..");
        rule.check(classes);
    }
}
