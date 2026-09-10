package com.aihub.ai.infra.storage;

import io.minio.MinioClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 入库原始文件存储的统一注册入口：{@code aihub.storage.type} 一个开关决定后端。
 *
 * <p>为什么收敛到一个配置类（与浏览器能力的 BrowserConfiguration 同思路）：
 * 开关分散是配置漂移的温床——两个实现都挂 @Component 时，
 * 「同时注册两个 IngestFileStore」会让按类型注入直接启动失败；
 * 收敛到一处后，后端切换只看一个键，且默认行为（local）与历史版本完全一致。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfiguration {

    /** 本地磁盘（默认，单机部署）；目录键沿用 aihub.rag.ingest-file-dir，不破坏既有配置 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "aihub.storage", name = "type", havingValue = "local", matchIfMissing = true)
    static class LocalStorage {

        @Bean
        public LocalIngestFileStore ingestFileStore(Environment environment) {
            return new LocalIngestFileStore(
                    environment.getProperty("aihub.rag.ingest-file-dir", ".aihub/ingest"));
        }
    }

    /**
     * MinIO / S3（多实例部署共享原始文件）。
     *
     * <p>MinioClient 的构建不做网络 IO（连接在使用时才建立），
     * 所以没有 MinIO 服务时上下文也能创建；真正的连通性检查发生在
     * 首次存取的懒建桶环节。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "aihub.storage", name = "type", havingValue = "minio")
    static class MinioStorage {

        @Bean
        public MinioClient minioClient(StorageProperties properties) {
            StorageProperties.Minio minio = properties.getMinio();
            return MinioClient.builder()
                    .endpoint(minio.getEndpoint())
                    .credentials(minio.getAccessKey(), minio.getSecretKey())
                    .build();
        }

        @Bean
        public MinioIngestFileStore ingestFileStore(MinioClient client, StorageProperties properties) {
            return new MinioIngestFileStore(client, properties);
        }
    }
}
