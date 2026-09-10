package com.aihub.ai.infra.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 入库原始文件的存储参数（aihub.storage.*）。
 *
 * <p>两种后端由 {@code type} 一个开关切换：
 * <ul>
 *   <li>{@code local}（默认）——本地磁盘，单机部署够用，目录沿用 {@code aihub.rag.ingest-file-dir}；</li>
 *   <li>{@code minio}——S3 协议对象存储（MinIO / 各云厂商兼容网关），
 *       多实例部署时共享原始文件，失败重试不再依赖「请求恰好落回存文件的节点」。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "aihub.storage")
public class StorageProperties {

    /** 存储后端：local / minio */
    private String type = "local";

    private final Minio minio = new Minio();

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Minio getMinio() {
        return minio;
    }

    /** S3 协议连接参数（type=minio 时生效） */
    public static class Minio {

        /** S3 API 地址（MinIO 默认 9000 端口；控制台是 9001，别填混） */
        private String endpoint = "http://127.0.0.1:9000";

        private String accessKey = "aihub";

        private String secretKey = "aihub12345";

        /** 入库文件桶名；不存在会在首次使用时自动创建 */
        private String bucket = "aihub-ingest";

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getAccessKey() {
            return accessKey;
        }

        public void setAccessKey(String accessKey) {
            this.accessKey = accessKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket;
        }
    }
}
