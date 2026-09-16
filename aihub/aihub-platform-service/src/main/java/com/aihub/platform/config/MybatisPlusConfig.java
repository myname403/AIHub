package com.aihub.platform.config;

import com.aihub.common.tenant.TenantContext;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * MyBatis-Plus 配置：分页插件 + 可选的租户 SQL 兜底。
 *
 * <p><b>分页插件为什么必须配：</b>MP 的 selectPage() 分两个动作——生成 LIMIT 子句（由本插件负责）
 * 和执行 COUNT 查询（也是本插件负责）。不配这个插件，selectPage 会退化成"查全表"，
 * 分页参数被静默忽略（经典的 MP 坑，本章学习文档有展开）。
 * 详见学习文档《07-RBAC与管理端.md》。
 *
 * <p><b>租户 SQL 兜底（默认关闭）：</b>把「每个 Repository 手写 tenant_id 条件」这条人工约定
 * 升级为框架级自动追加，漏写不再等于越权。开关：{@code aihub.tenant.sql-guard.enabled}。
 * 默认关闭的原因与验证步骤见 {@link #mybatisPlusInterceptor(boolean, String)}。
 */
@Slf4j
@Configuration
public class MybatisPlusConfig {

    /**
     * 不含租户列的表（平台库里的"全局表"）：
     * <ul>
     *   <li>{@code sys_tenant} —— 租户表本身（按 id/code 查，无所谓"租户内"）；</li>
     *   <li>{@code sys_menu} / {@code sys_role_menu} —— 菜单与授权是平台全局定义的，
     *       租户只做"授权"不做"定义"（见 V7 迁移脚本注释）。</li>
     * </ul>
     */
    private static final Set<String> GLOBAL_TABLES = Set.of("sys_tenant", "sys_menu", "sys_role_menu");

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(
            @Value("${aihub.tenant.sql-guard.enabled:false}") boolean tenantGuardEnabled,
            @Value("${aihub.tenant.sql-guard.ignore-tables:}") String extraIgnoreTables) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        if (tenantGuardEnabled) {
            // 顺序遵循 MP 官方建议：多租户 → 分页（先改 WHERE，再套 LIMIT/COUNT）
            interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(
                    new PlatformTenantLineHandler(splitToSet(extraIgnoreTables))));
            log.warn("★ 租户 SQL 兜底已开启：所有租户表将自动追加 tenant_id 条件，"
                    + "无租户上下文时跳过。请确认已在真实库上回归过数据访问链路。");
        }
        // 指定数据库类型：分页方言按 MySQL 生成 LIMIT offset,size
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    /** 逗号分隔的额外白名单 */
    private static Set<String> splitToSet(String raw) {
        Set<String> set = new LinkedHashSet<>();
        if (raw != null && !raw.isBlank()) {
            Arrays.stream(raw.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(s -> set.add(s.toLowerCase(Locale.ROOT)));
        }
        return set;
    }

    /**
     * 平台库的租户处理器。
     *
     * <p><b>★ 为什么整套兜底默认关闭：</b>它改的是每一条 SQL，影响面覆盖全部数据访问；
     * 而本模块的自动化测试是 Mockito 单测（Mapper 被 mock），
     * <b>覆盖不到真实 SQL 的解析与改写</b>。没有真实 MySQL 就无法证明"所有语句都能被正确改写"，
     * 因此采取「先落地、默认关、验证后再开」：
     * ① 测试环境起 MySQL，置 {@code aihub.tenant.sql-guard.enabled=true} 启动；
     * ② 跑登录 → 租户/用户/角色/菜单 CRUD → API Key 签发 → 配额扣减 → 用量看板；
     * ③ 日志无 SQL 异常后再考虑在生产开启。
     *
     * <p>安全设计：{@link TenantContext#getTenantId()} 为空（登录、内部接口）时
     * {@link PlatformTenantLineHandler#ignoreTable(String)} 返回 true，SQL 原样执行，
     * 行为与关闭时完全一致 —— 开启不会破坏这些既有链路。
     */
    static class PlatformTenantLineHandler implements TenantLineHandler {

        private final Set<String> extraIgnoreTables;

        PlatformTenantLineHandler(Set<String> extraIgnoreTables) {
            this.extraIgnoreTables = extraIgnoreTables;
        }

        @Override
        public String getTenantIdColumn() {
            return "tenant_id";
        }

        @Override
        public Expression getTenantId() {
            Long tenantId = TenantContext.getTenantId();
            return new LongValue(tenantId == null ? -1L : tenantId);
        }

        @Override
        public boolean ignoreTable(String tableName) {
            if (TenantContext.getTenantId() == null || tableName == null) {
                return true;
            }
            String normalized = tableName.toLowerCase(Locale.ROOT);
            return GLOBAL_TABLES.contains(normalized) || extraIgnoreTables.contains(normalized);
        }
    }
}
