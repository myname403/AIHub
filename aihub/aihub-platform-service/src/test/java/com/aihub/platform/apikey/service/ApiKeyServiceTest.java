package com.aihub.platform.apikey.service;

import com.aihub.common.exception.BizException;
import com.aihub.common.security.ApiKeyCodec;
import com.aihub.platform.apikey.entity.SysApiKeyDO;
import com.aihub.platform.apikey.mapper.SysApiKeyMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ApiKeyService 单测：聚焦安全属性而非流程覆盖率。
 *
 * <p>重点验证：明文绝不落库、停用/过期立即失效、租户由 Key 反查而非传参、跨租户吊销被拒。
 */
@ExtendWith(MockitoExtension.class)
class ApiKeyServiceTest {

    private static final String SECRET = "unit-test-secret";
    private static final Long TENANT = 1001L;

    @Mock
    private SysApiKeyMapper apiKeyMapper;

    private ApiKeyService apiKeyService;

    @BeforeEach
    void setUp() {
        apiKeyService = new ApiKeyService(apiKeyMapper);
        // @Value 字段在纯单测中不会被注入，手工补齐配置
        ReflectionTestUtils.setField(apiKeyService, "apiKeySecret", SECRET);
        ReflectionTestUtils.setField(apiKeyService, "maxKeysPerTenant", 20);
    }

    /* ---------------- 签发 ---------------- */

    @Test
    void issuedKeyIsReturnedOnceAndStoredOnlyAsHash() {
        when(apiKeyMapper.selectCount(any())).thenReturn(0L);
        stubInsertAssignsId();

        ApiKeyService.IssuedKey issued = apiKeyService.issue(TENANT, "生产环境", null, null);

        // 明文必须是可用的 ak_ 前缀 Key
        assertTrue(issued.plainKey().startsWith(ApiKeyCodec.PREFIX));
        assertEquals(51, issued.plainKey().length());

        ArgumentCaptor<SysApiKeyDO> captor = ArgumentCaptor.forClass(SysApiKeyDO.class);
        verify(apiKeyMapper).insert(captor.capture());
        SysApiKeyDO stored = captor.getValue();

        // 核心安全属性：库里存的是哈希，且与明文不同
        assertEquals(ApiKeyCodec.hash(issued.plainKey(), SECRET), stored.getKeyHash());
        assertNotEquals(issued.plainKey(), stored.getKeyHash());
        assertFalse(stored.getKeyHash().contains(issued.plainKey()),
                "哈希不应包含明文（HMAC 是单向的）");
        // 前缀只用于展示，不足以还原 Key
        assertEquals(issued.plainKey().substring(0, 7), stored.getKeyPrefix());
        assertTrue(stored.getKeyPrefix().length() < issued.plainKey().length() / 2,
                "前缀必须远比明文短，否则泄露熵过多");
    }

    @Test
    void newKeyIsEnabledAndCountersStartAtZero() {
        when(apiKeyMapper.selectCount(any())).thenReturn(0L);
        stubInsertAssignsId();

        LocalDateTime expireAt = LocalDateTime.now().plusDays(30);
        apiKeyService.issue(TENANT, "临时 Key", 2002L, expireAt);

        ArgumentCaptor<SysApiKeyDO> captor = ArgumentCaptor.forClass(SysApiKeyDO.class);
        verify(apiKeyMapper).insert(captor.capture());
        SysApiKeyDO stored = captor.getValue();

        assertEquals(1, stored.getStatus(), "新签发的 Key 必须立即可用");
        assertEquals(0L, stored.getUsedCount());
        assertEquals(TENANT, stored.getTenantId());
        assertEquals(2002L, stored.getAppId());
        assertEquals(expireAt, stored.getExpireAt());
    }

    @Test
    void blankNameIsRejected() {
        assertThrows(BizException.class, () -> apiKeyService.issue(TENANT, "  ", null, null));
        verify(apiKeyMapper, never()).insert(any(SysApiKeyDO.class));
    }

    @Test
    void issuingBeyondLimitIsRejected() {
        when(apiKeyMapper.selectCount(any())).thenReturn(20L);

        assertThrows(BizException.class, () -> apiKeyService.issue(TENANT, "第 21 个", null, null));
        verify(apiKeyMapper, never()).insert(any(SysApiKeyDO.class));
    }

    @Test
    void limitCountsOnlyActiveKeys() {
        // 已吊销的不占额度：selectCount 只查 status=1，返回 19 表示还能再签
        when(apiKeyMapper.selectCount(any())).thenReturn(19L);
        stubInsertAssignsId();

        apiKeyService.issue(TENANT, "第 20 个", null, null);

        verify(apiKeyMapper).insert(any(SysApiKeyDO.class));
    }

    /* ---------------- 校验 ---------------- */

    @Test
    void blankKeyIsRejectedWithoutHittingDatabase() {
        assertTrue(apiKeyService.verify(null).isEmpty());
        assertTrue(apiKeyService.verify("").isEmpty());
        verify(apiKeyMapper, never()).selectOne(any());
    }

    @Test
    void unknownKeyIsRejected() {
        when(apiKeyMapper.selectOne(any())).thenReturn(null);
        assertTrue(apiKeyService.verify("ak_deadbeef").isEmpty());
    }

    @Test
    void validKeyResolvesTenantFromDatabaseNotFromCaller() {
        SysApiKeyDO stored = row(1L, TENANT, 1, null);
        when(apiKeyMapper.selectOne(any())).thenReturn(stored);

        Optional<ApiKeyService.ApiKeyPrincipal> principal = apiKeyService.verify("ak_whatever");

        assertTrue(principal.isPresent());
        // 租户来自 Key 反查——调用方无法通过请求参数指定租户（安全红线 1）
        assertEquals(TENANT, principal.get().tenantId());
        assertEquals(1L, principal.get().keyId());
    }

    @Test
    void disabledKeyIsRejectedImmediately() {
        when(apiKeyMapper.selectOne(any())).thenReturn(row(1L, TENANT, 0, null));

        assertTrue(apiKeyService.verify("ak_whatever").isEmpty(),
                "status=0 的 Key 必须立即失效，无需等待缓存过期");
    }

    @Test
    void expiredKeyIsRejected() {
        when(apiKeyMapper.selectOne(any()))
                .thenReturn(row(1L, TENANT, 1, LocalDateTime.now().minusSeconds(1)));

        assertTrue(apiKeyService.verify("ak_whatever").isEmpty());
    }

    @Test
    void notYetExpiredKeyIsAccepted() {
        when(apiKeyMapper.selectOne(any()))
                .thenReturn(row(1L, TENANT, 1, LocalDateTime.now().plusMinutes(5)));

        assertTrue(apiKeyService.verify("ak_whatever").isPresent());
    }

    /* ---------------- 使用记录 ---------------- */

    @Test
    void touchIncrementsUsageCounter() {
        when(apiKeyMapper.selectById(anyLong())).thenReturn(row(1L, TENANT, 1, null));

        apiKeyService.touch(1L);

        ArgumentCaptor<SysApiKeyDO> captor = ArgumentCaptor.forClass(SysApiKeyDO.class);
        verify(apiKeyMapper).update(captor.capture(), any());
        assertNotNullLastUsed(captor.getValue());
        assertEquals(7L, captor.getValue().getUsedCount());
    }

    @Test
    void touchFailureDoesNotBreakAuthentication() {
        // 鉴权已通过，统计失败不该让请求失败
        when(apiKeyMapper.selectById(anyLong())).thenThrow(new RuntimeException("db down"));

        apiKeyService.touch(1L); // 不应抛出
    }

    /* ---------------- 列表与吊销 ---------------- */

    @Test
    void listExposesMetadataOnly() {
        SysApiKeyDO stored = row(1L, TENANT, 1, null);
        stored.setName("生产环境");
        stored.setKeyPrefix("ak_9f3c");
        when(apiKeyMapper.selectList(any())).thenReturn(List.of(stored));

        List<ApiKeyService.ApiKeyView> views = apiKeyService.list(TENANT);

        assertEquals(1, views.size());
        assertEquals("生产环境", views.get(0).name());
        assertEquals("ak_9f3c", views.get(0).prefix());
        assertEquals(6L, views.get(0).usedCount());
    }

    @Test
    void crossTenantRevokeIsRejected() {
        // Key 属于 1001，攻击者持 2002 租户身份尝试吊销
        when(apiKeyMapper.selectById(anyLong())).thenReturn(row(1L, TENANT, 1, null));

        assertFalse(apiKeyService.revoke(2002L, 1L), "跨租户吊销必须失败");
        verify(apiKeyMapper, never()).deleteById(anyLong());
    }

    @Test
    void missingKeyRevokeIsNoop() {
        when(apiKeyMapper.selectById(anyLong())).thenReturn(null);

        assertFalse(apiKeyService.revoke(TENANT, 999L));
        verify(apiKeyMapper, never()).deleteById(anyLong());
    }

    @Test
    void revokeWithinTenantSucceeds() {
        when(apiKeyMapper.selectById(anyLong())).thenReturn(row(1L, TENANT, 1, null));

        assertTrue(apiKeyService.revoke(TENANT, 1L));
        verify(apiKeyMapper).deleteById(1L);
    }

    /* ---------------- 辅助 ---------------- */

    private void stubInsertAssignsId() {
        when(apiKeyMapper.insert(any(SysApiKeyDO.class))).thenAnswer(inv -> {
            SysApiKeyDO arg = inv.getArgument(0);
            arg.setId(99L); // 模拟 MyBatis-Plus 回填主键
            return 1;
        });
    }

    private SysApiKeyDO row(Long id, Long tenantId, int status, LocalDateTime expireAt) {
        SysApiKeyDO entity = new SysApiKeyDO();
        entity.setId(id);
        entity.setTenantId(tenantId);
        entity.setStatus(status);
        entity.setExpireAt(expireAt);
        entity.setUsedCount(6L);
        return entity;
    }

    private void assertNotNullLastUsed(SysApiKeyDO patch) {
        assertTrue(patch.getLastUsedAt() != null, "使用记录应带上 last_used_at");
    }
}
