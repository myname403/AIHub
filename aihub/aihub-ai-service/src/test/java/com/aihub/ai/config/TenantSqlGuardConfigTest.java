package com.aihub.ai.config;

import com.aihub.common.tenant.TenantContext;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 租户 SQL 兜底的处理器单测。
 *
 * <p><b>覆盖范围声明（重要）：</b>本用例只验证「哪些表要打补丁、租户值取自哪里」这一层逻辑。
 * <b>SQL 改写本身（JSqlParser 解析 → 追加 tenant_id 条件）没有在这里执行</b>——
 * 它需要真实 MySQL 与完整 SQL 才能回归，这也是该能力默认关闭的原因
 * （见 {@link TenantSqlGuardConfig} 的类注释）。
 */
class TenantSqlGuardConfigTest {

    private final TenantLineHandler handler =
            new TenantSqlGuardConfig.AiTenantLineHandler(Set.of("ai_demo_dict"));

    @AfterEach
    void tearDown() {
        TenantContext.clear();   // ThreadLocal 必须清理，否则污染同线程的其它用例
    }

    @Test
    void tenantColumnNameFollowsConvention() {
        assertThat(handler.getTenantIdColumn()).isEqualTo("tenant_id");
    }

    @Test
    void globalTableIsNeverPatched() {
        TenantContext.set(7L, 9L);
        // 供应商字典是平台级共享数据（没有 tenant_id 列），必须跳过，否则 SQL 直接报错
        assertThat(handler.ignoreTable("ai_model_provider")).isTrue();
        // 表名大小写不敏感
        assertThat(handler.ignoreTable("AI_MODEL_PROVIDER")).isTrue();
    }

    @Test
    void extraIgnoreTablesFromConfigAreRespected() {
        TenantContext.set(7L, 9L);
        assertThat(handler.ignoreTable("ai_demo_dict")).isTrue();
    }

    @Test
    void tenantTablesArePatchedWhenTenantPresent() {
        TenantContext.set(7L, 9L);
        assertThat(handler.ignoreTable("ai_document")).isFalse();
        assertThat(handler.ignoreTable("ai_message")).isFalse();
        assertThat(handler.ignoreTable("ai_agent_task")).isFalse();
    }

    @Test
    void nothingIsPatchedWithoutTenantContext() {
        // 无租户上下文：登录、异步入库线程、内部接口等场景 —— 必须完全跳过，
        // 否则"开启兜底"会把这些既有链路一起打断
        TenantContext.clear();
        assertThat(handler.ignoreTable("ai_document")).isTrue();
        assertThat(handler.ignoreTable("ai_model_provider")).isTrue();
    }

    @Test
    void tenantValueComesFromContextOnly() {
        TenantContext.set(123456L, 9L);
        assertThat(handler.getTenantId().toString()).isEqualTo("123456");

        TenantContext.clear();
        // 无上下文时的兜底值（正常流程不会走到这里，见 ignoreTable 的前置拦截）
        assertThat(handler.getTenantId().toString()).isEqualTo("-1");
    }
}
