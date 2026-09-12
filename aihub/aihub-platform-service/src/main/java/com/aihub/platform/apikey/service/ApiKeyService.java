package com.aihub.platform.apikey.service;

import com.aihub.common.exception.BizException;
import com.aihub.common.result.ResultCode;
import com.aihub.common.security.ApiKeyCodec;
import com.aihub.platform.apikey.entity.SysApiKeyDO;
import com.aihub.platform.apikey.mapper.SysApiKeyMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 开放 API Key 服务（M5）：签发 / 校验 / 吊销。
 *
 * <p>安全设计：
 * <ul>
 *   <li>明文只在签发时返回一次，库中仅存 HMAC-SHA256 哈希；</li>
 *   <li>校验按哈希精确匹配（唯一索引），不遍历比对；</li>
 *   <li>状态与过期时间双重校验，停用或过期的 Key 立即失效；</li>
 *   <li>租户由 Key 反查得到——调用方无法通过传参指定租户（安全红线 1）。</li>
 * </ul>
 *
 * <p><b>MyBatis-Plus 用法提示（本类大量使用）：</b>
 * {@code Wrappers.lambdaQuery()} 构造类型安全的查询条件，
 * {@code SysApiKeyDO::getTenantId} 是方法引用，编译期就能发现字段名写错
 * （比手写 SQL 字符串 "tenant_id" 安全得多）。@Value 注入的配置项支持
 * "配置名:默认值"语法，配置中心没配时用默认值。
 * 详见学习文档《04-平台服务-aihub-platform-service.md》。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private final SysApiKeyMapper apiKeyMapper;

    /** HMAC 密钥：与 JWT 密钥分离，避免一处泄露牵连另一处 */
    @Value("${aihub.security.api-key-secret:aihub-default-apikey-secret-please-change-me}")
    private String apiKeySecret;

    /** 单租户 Key 数量上限，防止无节制签发 */
    @Value("${aihub.security.max-api-keys-per-tenant:20}")
    private int maxKeysPerTenant;

    /**
     * 签发新 Key。
     *
     * @return 明文 Key（仅此一次返回，调用方必须立即保存）
     */
    public IssuedKey issue(Long tenantId, String name, Long appId, LocalDateTime expireAt) {
        if (name == null || name.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "Key 名称不能为空");
        }
        Long existing = apiKeyMapper.selectCount(Wrappers.<SysApiKeyDO>lambdaQuery()
                .eq(SysApiKeyDO::getTenantId, tenantId)
                .eq(SysApiKeyDO::getStatus, 1));
        if (existing != null && existing >= maxKeysPerTenant) {
            throw new BizException(ResultCode.PARAM_ERROR,
                    "已达 Key 数量上限（" + maxKeysPerTenant + "），请先吊销不用的 Key");
        }

        String plainKey = ApiKeyCodec.generate();
        SysApiKeyDO entity = new SysApiKeyDO();
        entity.setTenantId(tenantId);
        entity.setAppId(appId);
        entity.setName(name);
        entity.setKeyHash(ApiKeyCodec.hash(plainKey, apiKeySecret));
        entity.setKeyPrefix(ApiKeyCodec.prefixOf(plainKey));
        entity.setStatus(1);
        entity.setExpireAt(expireAt);
        entity.setUsedCount(0L);
        entity.setCreateTime(LocalDateTime.now());
        apiKeyMapper.insert(entity);

        log.info("签发 API Key tenant={} id={} prefix={}", tenantId, entity.getId(),
                entity.getKeyPrefix());
        return new IssuedKey(entity.getId(), plainKey, entity.getKeyPrefix(), expireAt);
    }

    /**
     * 校验 Key 并返回归属信息。
     *
     * <p>这是鉴权热路径：一次索引查询 + 内存比对，不做任何远程调用。
     *
     * @return 校验通过时返回归属，否则 empty（不区分「不存在」与「已停用」，避免探测）
     */
    public Optional<ApiKeyPrincipal> verify(String plainKey) {
        if (plainKey == null || plainKey.isBlank()) {
            return Optional.empty();
        }
        String hash = ApiKeyCodec.hash(plainKey, apiKeySecret);
        SysApiKeyDO entity = apiKeyMapper.selectOne(Wrappers.<SysApiKeyDO>lambdaQuery()
                .eq(SysApiKeyDO::getKeyHash, hash)
                .last("limit 1"));
        if (entity == null) {
            return Optional.empty();
        }
        // 状态校验：停用的 Key 立即失效
        if (entity.getStatus() == null || entity.getStatus() != 1) {
            return Optional.empty();
        }
        // 过期校验：过期时间已到即拒绝
        if (entity.getExpireAt() != null && entity.getExpireAt().isBefore(LocalDateTime.now())) {
            log.info("API Key 已过期 id={} expireAt={}", entity.getId(), entity.getExpireAt());
            return Optional.empty();
        }
        return Optional.of(new ApiKeyPrincipal(
                entity.getId(), entity.getTenantId(), entity.getAppId(), entity.getName()));
    }

    /**
     * 记录使用（更新 last_used_at 与计数）。
     *
     * <p>失败只记日志——鉴权已通过，统计失败不该让请求失败。
     */
    public void touch(Long keyId) {
        try {
            SysApiKeyDO patch = new SysApiKeyDO();
            patch.setLastUsedAt(LocalDateTime.now());
            SysApiKeyDO current = apiKeyMapper.selectById(keyId);
            patch.setUsedCount(current == null || current.getUsedCount() == null
                    ? 1L : current.getUsedCount() + 1);
            apiKeyMapper.update(patch, Wrappers.<SysApiKeyDO>lambdaUpdate()
                    .eq(SysApiKeyDO::getId, keyId));
        } catch (Exception e) {
            log.warn("更新 API Key 使用记录失败 id={} err={}", keyId, e.getMessage());
        }
    }

    /** 列出本租户的 Key（只返回元信息，不含明文与哈希） */
    public List<ApiKeyView> list(Long tenantId) {
        return apiKeyMapper.selectList(Wrappers.<SysApiKeyDO>lambdaQuery()
                        .eq(SysApiKeyDO::getTenantId, tenantId)
                        .orderByDesc(SysApiKeyDO::getCreateTime))
                .stream()
                .map(k -> new ApiKeyView(k.getId(), k.getName(), k.getKeyPrefix(), k.getAppId(),
                        k.getStatus(), k.getExpireAt(), k.getLastUsedAt(),
                        k.getUsedCount() == null ? 0L : k.getUsedCount()))
                .toList();
    }

    /**
     * 吊销 Key（软删）。
     *
     * <p>必须带 tenantId 校验：否则可以用别人的 Key ID 越权吊销。
     */
    public boolean revoke(Long tenantId, Long keyId) {
        SysApiKeyDO entity = apiKeyMapper.selectById(keyId);
        if (entity == null || !tenantId.equals(entity.getTenantId())) {
            return false;
        }
        apiKeyMapper.deleteById(keyId);
        log.info("吊销 API Key tenant={} id={}", tenantId, keyId);
        return true;
    }

    /* ---------------- 返回值 ---------------- */

    /** 签发结果：明文 Key 仅此一次可得 */
    public record IssuedKey(Long id, String plainKey, String prefix, LocalDateTime expireAt) {
    }

    /** 鉴权主体：Key 映射出的租户与授权范围 */
    public record ApiKeyPrincipal(Long keyId, Long tenantId, Long appId, String name) {
    }

    /** 列表视图：刻意不含明文与哈希 */
    public record ApiKeyView(Long id, String name, String prefix, Long appId, Integer status,
                             LocalDateTime expireAt, LocalDateTime lastUsedAt, long usedCount) {
    }
}
