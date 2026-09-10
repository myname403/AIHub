package com.aihub.ai.application;

import com.aihub.ai.domain.model.ModelCommand;
import com.aihub.ai.domain.model.ModelInfo;
import com.aihub.ai.domain.model.ModelProvider;
import com.aihub.ai.domain.model.ModelRouteInfo;
import com.aihub.ai.domain.spi.ModelAdminRepository;
import com.aihub.common.exception.BizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModelAdminServiceTest {

    private static final Long TENANT = 1001L;

    @Mock
    private ModelAdminRepository modelAdminRepository;

    @InjectMocks
    private ModelAdminService modelAdminService;

    private void stubProviders() {
        when(modelAdminRepository.listProviders()).thenReturn(List.of(
                new ModelProvider("openai", "OpenAI 兼容", null),
                new ModelProvider("deepseek", "DeepSeek 官方", null)));
    }

    private ModelCommand command(String provider, String model, String apiKey) {
        return new ModelCommand(provider, model, apiKey, null, null, false, 1);
    }

    /* ---------------- 新建 ---------------- */

    @Test
    void createWithKnownProviderSucceeds() {
        stubProviders();
        when(modelAdminRepository.createModel(eq(TENANT), any())).thenReturn(7L);

        assertEquals(7L, modelAdminService.createModel(
                TENANT, command("openai", "gpt-4o-mini", "sk-xxx")));
    }

    /** 未知 provider 在运行时会静默退化成非兼容分支，模型直接调不通 */
    @Test
    void unknownProviderIsRejected() {
        stubProviders();

        assertThrows(BizException.class, () -> modelAdminService.createModel(
                TENANT, command("anthropic", "claude-3", "sk-xxx")));
        verify(modelAdminRepository, never()).createModel(anyLong(), any());
    }

    @Test
    void blankModelCodeIsRejected() {
        stubProviders();

        assertThrows(BizException.class, () -> modelAdminService.createModel(
                TENANT, command("openai", "  ", "sk-xxx")));
    }

    @Test
    void zeroVectorDimIsRejected() {
        stubProviders();

        assertThrows(BizException.class, () -> modelAdminService.createModel(TENANT,
                new ModelCommand("openai", "text-embedding-3-small", "sk", null, 0, false, 1)));
    }

    /** 本地 ollama 无需鉴权，新建时不该强制要求 Key */
    @Test
    void createWithoutApiKeyIsAllowed() {
        stubProviders();
        when(modelAdminRepository.createModel(eq(TENANT), any())).thenReturn(8L);

        assertEquals(8L, modelAdminService.createModel(
                TENANT, command("openai", "local-model", null)));
    }

    @Test
    void invalidStatusIsRejected() {
        stubProviders();

        assertThrows(BizException.class, () -> modelAdminService.createModel(TENANT,
                new ModelCommand("openai", "m", "k", null, null, false, 5)));
    }

    /** 默认模型必须唯一，否则哪条被取到取决于查询顺序 */
    @Test
    void creatingDefaultClearsOtherDefaults() {
        stubProviders();
        when(modelAdminRepository.createModel(eq(TENANT), any())).thenReturn(7L);

        modelAdminService.createModel(TENANT,
                new ModelCommand("openai", "gpt-4o", "sk", null, null, true, 1));

        verify(modelAdminRepository).clearDefaultFlag(TENANT, 7L);
    }

    @Test
    void creatingNonDefaultLeavesOthersUntouched() {
        stubProviders();
        when(modelAdminRepository.createModel(eq(TENANT), any())).thenReturn(7L);

        modelAdminService.createModel(TENANT, command("openai", "gpt-4o", "sk"));

        verify(modelAdminRepository, never()).clearDefaultFlag(anyLong(), anyLong());
    }

    /* ---------------- 更新 ---------------- */

    @Test
    void updateWithoutApiKeyKeepsStoredKey() {
        stubProviders();
        when(modelAdminRepository.updateModel(eq(TENANT), eq(7L), any())).thenReturn(true);

        // apiKey 为 null 表示「保持原值」——管理端拿不到明文，不能要求回填
        ModelCommand patch = new ModelCommand("openai", "gpt-4o", null, null, null, false, 1);
        modelAdminService.updateModel(TENANT, 7L, patch);

        verify(modelAdminRepository).updateModel(TENANT, 7L, patch);
    }

    @Test
    void updateOfForeignModelFails() {
        stubProviders();
        when(modelAdminRepository.updateModel(eq(TENANT), eq(777L), any())).thenReturn(false);

        assertThrows(BizException.class, () -> modelAdminService.updateModel(
                TENANT, 777L, command("openai", "gpt-4o", "sk")));
    }

    @Test
    void deletingMissingModelFails() {
        when(modelAdminRepository.deleteModel(eq(TENANT), eq(777L))).thenReturn(false);

        assertThrows(BizException.class, () -> modelAdminService.deleteModel(TENANT, 777L));
    }

    /* ---------------- 场景路由 ---------------- */

    @Test
    void unknownSceneIsRejected() {
        assertThrows(BizException.class,
                () -> modelAdminService.saveRoute(TENANT, "translation", 1L, null));
        verify(modelAdminRepository, never()).saveRoute(anyLong(), any(), any(), any());
    }

    @Test
    void bothModelsEmptyIsRejected() {
        assertThrows(BizException.class,
                () -> modelAdminService.saveRoute(TENANT, "chat", null, null));
    }

    /** 关键：不能把路由指向别的租户的模型 */
    @Test
    void routeToForeignModelIsRejected() {
        when(modelAdminRepository.listModels(TENANT)).thenReturn(List.of(model(1L, "gpt-4o")));

        assertThrows(BizException.class,
                () -> modelAdminService.saveRoute(TENANT, "chat", 999L, null));
        verify(modelAdminRepository, never()).saveRoute(anyLong(), any(), any(), any());
    }

    @Test
    void routeWithOwnedModelsSucceeds() {
        when(modelAdminRepository.listModels(TENANT))
                .thenReturn(List.of(model(1L, "gpt-4o"), model(2L, "deepseek-chat")));

        modelAdminService.saveRoute(TENANT, "chat", 1L, 2L);

        verify(modelAdminRepository).saveRoute(TENANT, "chat", 1L, 2L);
    }

    @Test
    void routeAllowsNullFallbackWhenPrimaryOwned() {
        when(modelAdminRepository.listModels(TENANT)).thenReturn(List.of(model(1L, "gpt-4o")));

        modelAdminService.saveRoute(TENANT, "embed", 1L, null);

        verify(modelAdminRepository).saveRoute(TENANT, "embed", 1L, null);
    }

    @Test
    void routesAreReturnedAsIs() {
        when(modelAdminRepository.listRoutes(TENANT)).thenReturn(List.of(
                new ModelRouteInfo(1L, "chat", 1L, "gpt-4o", 2L, "deepseek-chat")));

        assertEquals(1, modelAdminService.routes(TENANT).size());
    }

    /* ---------------- ModelCommand 的密钥遮蔽 ---------------- */

    /**
     * record 默认的 toString 会把所有字段拼进去；如果 apiKey 明文被日志打印出去就是密钥泄露。
     */
    @Test
    void commandToStringNeverLeaksPlainApiKey() {
        String secret = "sk-super-secret-value";
        ModelCommand cmd = command("openai", "gpt-4o", secret);

        assertFalse(cmd.toString().contains(secret), "toString 绝不能包含明文密钥");
        assertTrue(cmd.toString().contains("***"));
        assertTrue(cmd.apiKeyProvided());
    }

    @Test
    void blankApiKeyCountsAsNotProvided() {
        assertFalse(command("openai", "gpt-4o", "   ").apiKeyProvided());
        assertFalse(command("openai", "gpt-4o", null).apiKeyProvided());
    }

    private ModelInfo model(Long id, String code) {
        return new ModelInfo(id, "openai", code, null, null, false, 1, true);
    }
}
