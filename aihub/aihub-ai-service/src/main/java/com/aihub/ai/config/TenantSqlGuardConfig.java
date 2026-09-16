package com.aihub.ai.config;

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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 租户 SQL 兜底 —— 多租户隔离的「第五道防线」（默认关闭，见文末说明）。
 *
 * <p><b>要解决什么问题：</b>现有四道防线（源头只认 JWT / 网关防伪造 / 查询强制过滤 /
 * 出口 TenantGuard 校验）里，「查询强制过滤」是<b>人工约定</b>——每个 Repository 都要
 * 自己写 {@code .eq(Xxx::getTenantId, ...)} 或原生 SQL 带 {@code tenant_id}。
 * 一旦漏写，就是跨租户越权，而这类漏写在代码评审时很难被发现。
 * 本类把「约定」升级为「框架级兜底」：MyBatis-Plus 在生成 SQL 时自动追加租户条件，
 * 漏写不再等于越权。
 *
 * <p><b>工作原理：</b>{@link TenantLineInnerInterceptor} 在 SQL 执行前用 JSqlParser 解析语句：
 * <ul>
 *   <li>SELECT / UPDATE / DELETE → 在 WHERE 追加 {@code tenant_id = 当前租户}；</li>
 *   <li>INSERT → 若列清单里没有租户列，自动补上列与值（已显式写了的不会被重复追加）；</li>
 *   <li>无法解析或不在白名单的表 → 保持原 SQL 不动。</li>
 * </ul>
 *
 * <p><b>关键安全设计：没有租户上下文时一律跳过。</b>
 * 登录（尚不知道是哪个租户）、内部接口、入库异步线程、定时任务等场景
 * {@link TenantContext#getTenantId()} 为 null，此时不打补丁也不报错——
 * 保证「开启兜底」不会破坏这些既有链路，行为与开启前完全一致。
 *
 * <p><b>与四道防线的关系：</b>这是「兜底」不是「替代」。代码里显式写的租户条件继续保留
 * （重复条件是冗余但无害的），API Key / JWT 仍是唯一的租户来源。
 *
 * <p><b>★ 为什么默认关闭（aihub.tenant.sql-guard.enabled 默认 false）：</b>
 * 它改的是<b>每一条 SQL</b>，影响面覆盖全部数据访问。而本项目的自动化测试
 * （包含本模块约 160 个用例）以 Mockito 单测为主，Mapper 是 mock 的，
 * <b>覆盖不到真实 SQL 的解析与改写</b>；没有真实 MySQL 就无法证明「所有语句都能被正确改写」。
 * 因此当前实现采取「先落地、默认关、验证后再开」的策略：
 * <ol>
 *   <li>在测试环境起 MySQL，把本开关置 true 启动 ai-service；</li>
 *   <li>跑一遍关键链路：登录 → 对话 → 知识库上传/检索 → Agent → 用量看板 → 管理端 CRUD；</li>
 *   <li>观察日志无 SQL 异常后，再考虑在预发/生产开启。</li>
 * </ol>
 * 开启方式（任选）：环境变量 {@code AIHUB_TENANT_SQL_GUARD_ENABLED=true}，
 * 或 Nacos / application.yml 里配置 {@code aihub.tenant.sql-guard.enabled: true}。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "aihub.tenant.sql-guard", name = "enabled", havingValue = "true")
public class TenantSqlGuardConfig {

    /**
     * 不含租户列的表（不需要也无法追加租户条件）：
     * {@code ai_model_provider} 是平台级共享的供应商字典（uk_code 全局唯一），
     * 所有租户共用同一份，属于"全局表"而非"租户表"。
     */
    private static final Set<String> GLOBAL_TABLES = Set.of("ai_model_provider");

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(
            @Value("${aihub.tenant.sql-guard.ignore-tables:}") String extraIgnoreTables) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 顺序遵循 MP 官方建议：多租户 → 分页（先改 WHERE，再套 LIMIT/COUNT，避免分页 SQL 难以解析）
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(
                new AiTenantLineHandler(splitToSet(extraIgnoreTables))));
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        log.warn("★ 租户 SQL 兜底已开启（aihub.tenant.sql-guard.enabled=true）："
                + "所有租户表将自动追加 tenant_id 条件，无租户上下文时跳过。"
                + "请确认已在真实库上回归过全部数据访问链路。");
        return interceptor;
    }

    /** 逗号分隔的额外白名单（配置化，便于临时接入不含租户列的字典表） */
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
     * 租户处理器：只做两件事——判断该表要不要打补丁、给出当前租户值。
     *
     * <p>注意 {@link #ignoreTable(String)} 里的「无租户上下文即忽略」分支：
     * 它让兜底对登录、异步入库、内部接口等场景完全透明。
     */
    static class AiTenantLineHandler implements TenantLineHandler {

        private final Set<String> extraIgnoreTables;

        AiTenantLineHandler(Set<String> extraIgnoreTables) {
            this.extraIgnoreTables = extraIgnoreTables;
        }

        /** 租户列名：与所有表的列名约定一致 */
        @Override
        public String getTenantIdColumn() {
            return "tenant_id";
        }

        /** 租户值：只能来自 TenantContext（JWT / API Key 解析后的写入），不接受任何外部入参 */
        @Override
        public Expression getTenantId() {
            Long tenantId = TenantContext.getTenantId();
            // 兜底值仅用于防御：正常流程下 ignoreTable 已把"无租户"的语句挡在外面，
            // 不会走到这里以 -1 去查数据。
            return new LongValue(tenantId == null ? -1L : tenantId);
        }

        /** 是否跳过该表：全局表、额外白名单，以及「当前线程没有租户上下文」三种情况 */
        @Override
        public boolean ignoreTable(String tableName) {
            if (TenantContext.getTenantId() == null) {
                return true;
            }
            if (tableName == null) {
                return true;
            }
            String normalized = tableName.toLowerCase(Locale.ROOT);
            return GLOBAL_TABLES.contains(normalized) || extraIgnoreTables.contains(normalized);
        }
    }
}
