package com.aihub.ai.infra.ai;

import org.springframework.ai.embedding.EmbeddingModel;
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
 */
@Configuration
public class VectorStoreConfig {

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
