package com.aihub.platform.config;

import com.aihub.common.tenant.TenantContext;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 平台库租户 SQL 兜底的处理器单测。
 *
 * <p><b>覆盖范围声明：</b>只验证"哪些表打补丁、租户值取自哪里"。
 * SQL 改写本身（JSqlParser 解析 + 追加 tenant_id）需要真实 MySQL 才能回归，
 * 这也是该能力默认关闭的原因（见 {@link MybatisPlusConfig}）。
 *
 * <p>白名单的可信来源是迁移脚本：sys_menu / sys_role_menu 在 V7 中明确<b>不带</b> tenant_id
 * （菜单是平台全局定义的），sys_tenant 是租户表本身。
 */
class MybatisPlusConfigTest {

    private final TenantLineHandler handler =
            new MybatisPlusConfig.PlatformTenantLineHandler(Set.of("sys_dict_demo"));

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void globalTablesAreNeverPatched() {
        TenantContext.set(7L, 9L);
        // 这三张表没有 tenant_id 列，若被追加条件会直接导致 SQL 报错
        assertThat(handler.ignoreTable("sys_tenant")).isTrue();
        assertThat(handler.ignoreTable("sys_menu")).isTrue();
        assertThat(handler.ignoreTable("sys_role_menu")).isTrue();
    }

    @Test
    void tenantScopedTablesArePatched() {
        TenantContext.set(7L, 9L);
        assertThat(handler.ignoreTable("sys_user")).isFalse();
        assertThat(handler.ignoreTable("sys_role")).isFalse();
        assertThat(handler.ignoreTable("sys_api_key")).isFalse();
        assertThat(handler.ignoreTable("ai_quota_usage")).isFalse();
        assertThat(handler.ignoreTable("ai_usage_record")).isFalse();
        assertThat(handler.ignoreTable("ai_audit_log")).isFalse();
    }

    @Test
    void nothingIsPatchedWithoutTenantContext() {
        // 登录链路（/auth/login 时还不知道租户）必须保持原样，否则开启兜底会直接打断登录
        TenantContext.clear();
        assertThat(handler.ignoreTable("sys_user")).isTrue();
        assertThat(handler.ignoreTable("sys_tenant")).isTrue();
    }

    @Test
    void extraIgnoreTablesAndColumnName() {
        TenantContext.set(7L, 9L);
        assertThat(handler.ignoreTable("sys_dict_demo")).isTrue();
        assertThat(handler.getTenantIdColumn()).isEqualTo("tenant_id");
    }

    @Test
    void tenantValueComesFromContextOnly() {
        TenantContext.set(1001L, 9L);
        assertThat(handler.getTenantId().toString()).isEqualTo("1001");

        TenantContext.clear();
        assertThat(handler.getTenantId().toString()).isEqualTo("-1");
    }
}
