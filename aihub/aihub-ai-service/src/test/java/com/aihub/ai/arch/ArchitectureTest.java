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

    /** 1) 领域层保持纯净：不依赖上层，也不依赖任何框架（含 Spring 全家桶与 JSON 库） */
    @Test
    void domainShouldBeFrameworkFree() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..aihub.ai.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..aihub.ai.application..",
                        "..aihub.ai.web..",
                        "..aihub.ai.infra..",
                        // 全量 Spring（原先只禁了 spring.ai，实测 domain 一个 Spring 类都没用，
                        // 收紧到 org.springframework.. 才能防住"随手引个 StringUtils"式的渐进腐化）
                        "org.springframework..",
                        "com.baomidou..",
                        "com.fasterxml..",
                        "org.springframework.data.redis..");
        rule.check(classes);
    }

    /** 2) 编排层只认接口：不得依赖基础设施与表现层，也不得直接触碰 Spring AI / ORM / Redis 客户端 */
    @Test
    void applicationShouldNotDependOnInfraOrWeb() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..aihub.ai.application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..aihub.ai.infra..",
                        "..aihub.ai.web..",
                        // 模型调用必须经 domain 的 ChatExecutor / ModelGateway 端口，
                        // 编排层出现 Spring AI 类型即意味着"AI 能力被焊死在业务逻辑里"
                        "org.springframework.ai..",
                        "com.baomidou..",
                        "org.springframework.data.redis..");
        rule.check(classes);
    }

    /** 3) 表现层不得直接触碰 Spring AI 与持久化 / 基础设施类型 */
    @Test
    void webShouldNotTouchSpringAiOrInfra() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..aihub.ai.web..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.ai..",
                        "com.baomidou..",
                        "org.springframework.data.redis..",
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

    /**
     * 5) 基础设施底层不得反向依赖 AI 层（补上第 4 条只卡了一个方向的缺口）。
     *
     * <p>持久化 / 安全 / 存储 / 支撑这些底层包是被 AI 层依赖的，一旦反向依赖
     * 就形成环（Persistence → Ai → Persistence），SPI 端口的分层意义也就没了。
     */
    @Test
    void lowLevelInfraShouldNotDependOnAiLayer() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(
                        "..aihub.ai.infra.persistence..",
                        "..aihub.ai.infra.security..",
                        "..aihub.ai.infra.storage..",
                        "..aihub.ai.infra.support..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..aihub.ai.infra.ai..",
                        "..aihub.ai.infra.browser..");
        rule.check(classes);
    }

    /**
     * 6) AI 层不得依赖浏览器实现，只能依赖 domain 的 BrowserDriver / BrowserSessionManager 端口。
     *
     * <p>方向必须是 browser → ai（由 BrowserConfiguration 这一个装配类把实现注入 AI 层），
     * 反过来则意味着 AI 能力与"本机 Chrome"这个具体实现焊死。
     */
    @Test
    void aiLayerShouldNotDependOnBrowserImplementation() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..aihub.ai.infra.ai..")
                .should().dependOnClassesThat().resideInAPackage("..aihub.ai.infra.browser..");
        rule.check(classes);
    }

    /** 7) 浏览器能力不得直接读写数据库（数据访问统一走 domain 端口 + persistence 实现） */
    @Test
    void browserShouldNotTouchPersistence() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..aihub.ai.infra.browser..")
                .should().dependOnClassesThat().resideInAPackage("..aihub.ai.infra.persistence..");
        rule.check(classes);
    }
}
