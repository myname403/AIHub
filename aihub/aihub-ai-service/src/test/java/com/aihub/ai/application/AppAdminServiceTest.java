package com.aihub.ai.application;

import com.aihub.ai.domain.model.App;
import com.aihub.ai.domain.spi.AppRepository;
import com.aihub.common.exception.BizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppAdminServiceTest {

    private static final Long TENANT = 1001L;

    @Mock
    private AppRepository appRepository;

    @InjectMocks
    private AppAdminService appAdminService;

    /* ---------------- 新建 ---------------- */

    @Test
    void createForcesTenantFromContextAndIgnoresPayloadId() {
        when(appRepository.create(any())).thenReturn(9001L);

        App payload = new App();
        payload.setName("天机AI助手");
        // 攻击者尝试塞 tenantId 与 id —— 两者都必须被服务端覆盖
        payload.setTenantId(9999L);
        payload.setId(12345L);

        appAdminService.create(TENANT, payload);

        ArgumentCaptor<App> captor = ArgumentCaptor.forClass(App.class);
        verify(appRepository).create(captor.capture());
        assertEquals(TENANT, captor.getValue().getTenantId(), "tenantId 必须取自上下文");
        assertNull(captor.getValue().getId(), "新建时客户端传入的 id 必须被丢弃");
    }

    @Test
    void createFillsDefaults() {
        when(appRepository.create(any())).thenReturn(1L);

        App payload = new App();
        payload.setName("最小应用");
        appAdminService.create(TENANT, payload);

        ArgumentCaptor<App> captor = ArgumentCaptor.forClass(App.class);
        verify(appRepository).create(captor.capture());
        App saved = captor.getValue();
        assertEquals("none", saved.getAgentStrategy());
        assertEquals("window", saved.getMemoryPolicy());
        assertEquals(0.7d, saved.getTemperature());
        assertEquals(1, saved.getStatus());
    }

    @Test
    void blankNameIsRejected() {
        App payload = new App();
        payload.setName("   ");

        assertThrows(BizException.class, () -> appAdminService.create(TENANT, payload));
        verify(appRepository, never()).create(any());
    }

    @Test
    void overlongNameIsRejected() {
        App payload = new App();
        payload.setName("x".repeat(129));

        assertThrows(BizException.class, () -> appAdminService.create(TENANT, payload));
    }

    /**
     * 脏枚举不会立刻报错，而是在运行时静默走默认分支——
     * 所以必须在写入前拦住，否则用户会看到「配了 react 却是普通对话」。
     */
    @Test
    void unknownAgentStrategyIsRejected() {
        App payload = new App();
        payload.setName("ok");
        payload.setAgentStrategy("magic");

        assertThrows(BizException.class, () -> appAdminService.create(TENANT, payload));
        verify(appRepository, never()).create(any());
    }

    @Test
    void unknownMemoryPolicyIsRejected() {
        App payload = new App();
        payload.setName("ok");
        payload.setMemoryPolicy("infinite");

        assertThrows(BizException.class, () -> appAdminService.create(TENANT, payload));
    }

    @Test
    void outOfRangeTemperatureIsRejected() {
        App payload = new App();
        payload.setName("ok");
        payload.setTemperature(2.5d);

        assertThrows(BizException.class, () -> appAdminService.create(TENANT, payload));
    }

    @Test
    void validStrategiesAreAccepted() {
        when(appRepository.create(any())).thenReturn(1L);

        App payload = new App();
        payload.setName("ok");
        payload.setAgentStrategy("plan_execute");
        payload.setMemoryPolicy("summary");
        payload.setTemperature(2.0d);

        appAdminService.create(TENANT, payload);
        verify(appRepository).create(any());
    }

    /* ---------------- 更新 / 删除 ---------------- */

    @Test
    void updatePropagatesTenantAndAppId() {
        when(appRepository.update(eq(TENANT), any())).thenReturn(true);

        App payload = new App();
        payload.setName("改过名");
        appAdminService.update(TENANT, 9001L, payload);

        ArgumentCaptor<App> captor = ArgumentCaptor.forClass(App.class);
        verify(appRepository).update(eq(TENANT), captor.capture());
        assertEquals(9001L, captor.getValue().getId());
        assertEquals(TENANT, captor.getValue().getTenantId());
    }

    /** 仓储返回 0 行说明 (tenantId, appId) 没匹配上——可能是不存在，也可能是别人的应用 */
    @Test
    void updateOfForeignAppFails() {
        when(appRepository.update(eq(TENANT), any())).thenReturn(false);

        App payload = new App();
        payload.setName("改别人的");

        assertThrows(BizException.class, () -> appAdminService.update(TENANT, 8888L, payload));
    }

    @Test
    void deleteOfForeignAppFails() {
        when(appRepository.delete(eq(TENANT), anyLong())).thenReturn(false);

        assertThrows(BizException.class, () -> appAdminService.delete(TENANT, 8888L));
    }

    @Test
    void deleteSuccessIsSilent() {
        when(appRepository.delete(eq(TENANT), anyLong())).thenReturn(true);

        appAdminService.delete(TENANT, 9001L);

        verify(appRepository).delete(TENANT, 9001L);
    }

    /* ---------------- 知识库绑定 ---------------- */

    @Test
    void boundKnowledgeBasesChecksOwnershipFirst() {
        when(appRepository.find(TENANT, 8888L)).thenReturn(java.util.Optional.empty());

        assertThrows(BizException.class,
                () -> appAdminService.boundKnowledgeBases(TENANT, 8888L));
        verify(appRepository, never()).knowledgeBaseIds(anyLong(), anyLong());
    }

    @Test
    void boundKnowledgeBasesReturnsIdsForOwnedApp() {
        App owned = new App();
        owned.setId(9001L);
        when(appRepository.find(TENANT, 9001L)).thenReturn(java.util.Optional.of(owned));
        when(appRepository.knowledgeBaseIds(TENANT, 9001L)).thenReturn(List.of(9001L, 9002L));

        assertEquals(List.of(9001L, 9002L), appAdminService.boundKnowledgeBases(TENANT, 9001L));
    }
}
