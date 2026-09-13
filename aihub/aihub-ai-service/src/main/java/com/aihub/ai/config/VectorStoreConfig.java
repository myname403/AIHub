package com.aihub.ai.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

/**
 * 向量库装配（Redis Stack / RediSearch）。
 *
 * <p>要点：
 * <ul>
 *   <li>必须用 <b>redis-stack</b>（含 Redis Query Engine），普通 Redis 无向量能力</li>
 *   <li>元数据 tenant_id / kb_id / doc_id 建为 TAG 字段，供检索时强制过滤（租户隔离防线之二）</li>
 *   <li>initializeSchema=true 由应用启动时创建索引；生产建议别名 + v{n} 版本化实现零停机重建</li>
 * </ul>
 *
 * <p><b>嵌入模型（本期真实事故的修复）：</b>检索前要把问题向量化。不显式配置时
 * Spring AI 自动装配的 OpenAI 嵌入模型会去连 api.openai.com——网络不通时
 * <b>无限阻塞</b>，导致绑定过知识库的应用（如种子应用 9001）所有对话卡死在"思考中"。
 * 这里显式指向本地 Ollama 的 OpenAI 兼容嵌入接口（模型 embeddinggemma，768 维），
 * 离线可用、无外部依赖。生产换云供应商时改 aihub.embedding.* 三个配置项即可。
 */
@Configuration
public class VectorStoreConfig {

    /**
     * 嵌入模型：走 Ollama 的 OpenAI 兼容 /v1/embeddings。
     * 定义了本 Bean 后，Spring AI 的 OpenAI 嵌入自动装配自动退位（@ConditionalOnMissingBean）。
     */
    @Bean
    public EmbeddingModel embeddingModel(
            @Value("${aihub.embedding.base-url:http://localhost:11434}") String baseUrl,
            @Value("${aihub.embedding.api-key:ollama}") String apiKey,
            @Value("${aihub.embedding.model:embeddinggemma}") String model) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();
        // 1.1.5 的 OpenAiEmbeddingModel 无 builder，用构造器：API + 元数据模式 + 选项 + 重试模板
        return new OpenAiEmbeddingModel(
                api,
                org.springframework.ai.document.MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder().model(model).build(),
                org.springframework.retry.support.RetryTemplate.builder()
                        .maxAttempts(2)
                        .fixedBackoff(500)
                        .build());
    }

    @Bean
    public VectorStore vectorStore(
            EmbeddingModel embeddingModel,
            @Value("${spring.data.redis.host:127.0.0.1}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.password:}") String password,
            @Value("${spring.ai.vectorstore.redis.index-name:spring-ai-index}") String indexName,
            @Value("${spring.ai.vectorstore.redis.prefix:embedding:}") String prefix) {

        URI uri = password == null || password.isBlank()
                ? URI.create("redis://" + host + ":" + port)
                : URI.create("redis://:" + password + "@" + host + ":" + port);

        return RedisVectorStore.builder(new redis.clients.jedis.JedisPooled(uri), embeddingModel)
                .indexName(indexName)
                .prefix(prefix)
                .metadataFields(
                        RedisVectorStore.MetadataField.tag("tenant_id"),
                        RedisVectorStore.MetadataField.tag("kb_id"),
                        RedisVectorStore.MetadataField.tag("doc_id"))
                .initializeSchema(true)
                .build();
    }
}
