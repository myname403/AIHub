package com.aihub.ai.infra.storage;

import lombok.Data;
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
 *
 * <p>Lombok 说明：外层 {@code minio} 字段是 final 的，@Data 只为它生成 getter
 * （嵌套对象由 Spring 绑定器直接填充内部字段），其余可变字段照常生成读写方法。
 */
@Data
@ConfigurationProperties(prefix = "aihub.storage")
public class StorageProperties {

    /** 存储后端：local / minio */
    private String type = "local";

    private final Minio minio = new Minio();

    /** S3 协议连接参数（type=minio 时生效） */
    @Data
    public static class Minio {

        /** S3 API 地址（MinIO 默认 9000 端口；控制台是 9001，别填混） */
        private String endpoint = "http://127.0.0.1:9000";

        private String accessKey = "aihub";

        private String secretKey = "aihub12345";

        /** 入库文件桶名；不存在会在首次使用时自动创建 */
        private String bucket = "aihub-ingest";
    }
}
