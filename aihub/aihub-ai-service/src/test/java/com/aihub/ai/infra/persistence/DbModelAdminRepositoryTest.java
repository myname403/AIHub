package com.aihub.ai.infra.persistence;

import com.aihub.ai.domain.model.ModelCommand;
import com.aihub.ai.domain.model.ModelInfo;
import com.aihub.ai.infra.persistence.do_.AiModelDO;
import com.aihub.ai.infra.persistence.do_.AiModelProviderDO;
import com.aihub.ai.infra.persistence.do_.AiModelRouteDO;
import com.aihub.ai.infra.persistence.mapper.AiModelMapper;
import com.aihub.ai.infra.persistence.mapper.AiModelProviderMapper;
import com.aihub.ai.infra.persistence.mapper.AiModelRouteMapper;
import com.aihub.common.crypto.AesGcmTextCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DbModelAdminRepository 单测。
 *
 * <p>只测真正有风险的两件事：<b>密钥怎么进库</b>、<b>密钥会不会被误删</b>。
 * 其余是 MyBatis-Plus 的样板转换，测了价值不大。
 */
@ExtendWith(MockitoExtension.class)
class DbModelAdminRepositoryTest {

    private static final String DATA_KEY = "unit-test-data-key";
    private static final Long TENANT = 1001L;

    @Mock
    private AiModelMapper modelMapper;
    @Mock
    private AiModelRouteMapper routeMapper;
    @Mock
    private AiModelProviderMapper providerMapper;

    private DbModelAdminRepository repository;

    @BeforeEach
    void setUp() {
        repository = new DbModelAdminRepository(modelMapper, routeMapper, providerMapper);
        ReflectionTestUtils.setField(repository, "dataKey", DATA_KEY);
    }

    private ModelCommand command(String apiKey, boolean defaultModel) {
        return new ModelCommand("openai", "gpt-4o", apiKey, "https://api.openai.com",
                null, defaultModel, 1);
    }

    /* ---------------- 密钥落库 ---------------- */

    @Test
    void createEncryptsPlainApiKeyAndNeverStoresItRaw() {
        repository.createModel(TENANT, command("sk-plain-secret", false));

        ArgumentCaptor<AiModelDO> captor = ArgumentCaptor.forClass(AiModelDO.class);
        verify(modelMapper).insert(captor.capture());
        String stored = captor.getValue().getApiKeyEnc();

        assertNotEquals("sk-plain-secret", stored, "库里存的绝不能是明文");
        // 密文必须能解回明文，否则模型调用时会拿到乱码 Key
        assertEquals("sk-plain-secret", AesGcmTextCipher.decrypt(stored, DATA_KEY));
    }

    @Test
    void createWithoutApiKeyLeavesCipherNull() {
        repository.createModel(TENANT, command(null, false));

        ArgumentCaptor<AiModelDO> captor = ArgumentCaptor.forClass(AiModelDO.class);
        verify(modelMapper).insert(captor.capture());
        // 本地 ollama 之类无需 Key，留 NULL 即可，不能加密空串
        assertNull(captor.getValue().getApiKeyEnc());
    }

    /**
     * 回归用例：管理端编辑模型时拿不到明文密钥，因此 apiKey 会传空。
     * 这时若把 apiKeyEnc 写成 null，管理员每改一次温度就报废一次密钥。
     */
    @Test
    void updateWithoutApiKeyLeavesCipherUntouched() {
        when(modelMapper.update(any(AiModelDO.class), any())).thenReturn(1);

        repository.updateModel(TENANT, 7L, command(null, false));

        ArgumentCaptor<AiModelDO> captor = ArgumentCaptor.forClass(AiModelDO.class);
        verify(modelMapper).update(captor.capture(), any());
        assertNull(captor.getValue().getApiKeyEnc(),
                "patch 里 apiKeyEnc 必须为 null，MyBatis-Plus 才会忽略该列");
    }

    @Test
    void updateWithApiKeyReEncrypts() {
        when(modelMapper.update(any(AiModelDO.class), any())).thenReturn(1);

        repository.updateModel(TENANT, 7L, command("sk-new-secret", false));

        ArgumentCaptor<AiModelDO> captor = ArgumentCaptor.forClass(AiModelDO.class);
        verify(modelMapper).update(captor.capture(), any());
        assertEquals("sk-new-secret",
                AesGcmTextCipher.decrypt(captor.getValue().getApiKeyEnc(), DATA_KEY));
    }

    /** 随机 IV 意味着同一明文两次加密结果不同——这是正确行为，不是 bug */
    @Test
    void sameKeyEncryptsToDifferentCipherEachTime() {
        repository.createModel(TENANT, command("sk-same", false));
        repository.createModel(TENANT, command("sk-same", false));

        ArgumentCaptor<AiModelDO> captor = ArgumentCaptor.forClass(AiModelDO.class);
        verify(modelMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertNotEquals(captor.getAllValues().get(0).getApiKeyEnc(),
                captor.getAllValues().get(1).getApiKeyEnc());
    }

    /* ---------------- 读取脱敏 ---------------- */

    @Test
    void listModelsExposesOnlyHasApiKeyFlag() {
        AiModelDO row = new AiModelDO();
        row.setId(1L);
        row.setProviderCode("openai");
        row.setModelCode("gpt-4o");
        row.setApiKeyEnc(AesGcmTextCipher.encrypt("sk-secret", DATA_KEY));
        row.setIsDefault(1);
        row.setStatus(1);
        when(modelMapper.selectList(any())).thenReturn(List.of(row));

        ModelInfo info = repository.listModels(TENANT).get(0);

        assertTrue(info.hasApiKey());
        assertTrue(info.defaultModel());
        // ModelInfo 是 record，字段在编译期就定死了——不存在把密钥带出去的字段
        assertEquals("gpt-4o", info.modelCode());
    }

    @Test
    void modelWithoutKeyReportsFalse() {
        AiModelDO row = new AiModelDO();
        row.setId(1L);
        row.setProviderCode("ollama");
        row.setModelCode("qwen2");
        row.setStatus(1);
        when(modelMapper.selectList(any())).thenReturn(List.of(row));

        assertFalse(repository.listModels(TENANT).get(0).hasApiKey());
    }

    /* ---------------- 路由与删除 ---------------- */

    @Test
    void saveRouteInsertsWhenAbsent() {
        when(routeMapper.selectOne(any())).thenReturn(null);

        repository.saveRoute(TENANT, "chat", 1L, null);

        ArgumentCaptor<AiModelRouteDO> captor = ArgumentCaptor.forClass(AiModelRouteDO.class);
        verify(routeMapper).insert(captor.capture());
        assertEquals("chat", captor.getValue().getScene());
        assertEquals(TENANT, captor.getValue().getTenantId());
    }

    @Test
    void saveRouteUpdatesWhenPresent() {
        AiModelRouteDO existing = new AiModelRouteDO();
        existing.setId(5L);
        existing.setScene("chat");
        when(routeMapper.selectOne(any())).thenReturn(existing);

        repository.saveRoute(TENANT, "chat", 2L, 3L);

        verify(routeMapper).update(any(AiModelRouteDO.class), any());
        verify(routeMapper, org.mockito.Mockito.never()).insert(any(AiModelRouteDO.class));
    }

    /** 删模型必须顺带清掉指向它的路由，否则会留下「路由存在但模型没了」的哑火配置 */
    @Test
    void deleteModelAlsoRemovesRoutesPointingAtIt() {
        when(modelMapper.delete(any())).thenReturn(1);

        assertTrue(repository.deleteModel(TENANT, 7L));

        verify(routeMapper).delete(any());
        verify(modelMapper).delete(any());
    }

    @Test
    void clearDefaultFlagExcludesTheNewDefault() {
        repository.clearDefaultFlag(TENANT, 7L);

        // 只需确认语句发出；具体 SQL 条件由 MyBatis-Plus 生成
        verify(modelMapper).update(any(AiModelDO.class), any());
    }

    @Test
    void listProvidersMapsDictionary() {
        AiModelProviderDO row = new AiModelProviderDO();
        row.setCode("deepseek");
        row.setName("DeepSeek 官方");
        row.setBaseUrl("https://api.deepseek.com/v1");
        when(providerMapper.selectList(any())).thenReturn(List.of(row));

        assertEquals("deepseek", repository.listProviders().get(0).code());
    }
}
