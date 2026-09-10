package com.aihub.ai.infra.storage;

import com.aihub.ai.domain.spi.IngestFileStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 存储后端开关钉住测试：aihub.storage.type 决定 IngestFileStore 的实现。
 * 这个语义必须钉住——两个实现同时注册会让按类型注入直接启动失败；
 * 默认值必须是 local（与历史版本行为一致），不能悄悄变成 minio。
 */
class StorageConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StorageConfiguration.class);

    @Test
    void localByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(IngestFileStore.class);
            assertThat(context.getBean(IngestFileStore.class)).isInstanceOf(LocalIngestFileStore.class);
        });
    }

    @Test
    void explicitLocalStillLocal() {
        runner.withPropertyValues("aihub.storage.type=local").run(context ->
                assertThat(context.getBean(IngestFileStore.class)).isInstanceOf(LocalIngestFileStore.class));
    }

    @Test
    void minioWhenTypeSet() {
        // MinioClient 构建期不做网络 IO：没有 MinIO 服务也能安全创建上下文（桶是懒建的）
        runner.withPropertyValues("aihub.storage.type=minio").run(context -> {
            assertThat(context).hasSingleBean(IngestFileStore.class);
            assertThat(context.getBean(IngestFileStore.class)).isInstanceOf(MinioIngestFileStore.class);
        });
    }

    @Test
    void unknownTypeRegistersNothingAndFailsFast() {
        // 打错字（如 miniio）不能静默回落本地盘——那会让多实例部署悄悄退化成
        // 「各节点各存各的」，重试照样挑节点。注册不到实现时按类型注入在启动期
        // 直接失败，把配置错误暴露在部署阶段（fail-fast 优先于可用性）。
        runner.withPropertyValues("aihub.storage.type=miniio").run(context ->
                assertThat(context.getBeansOfType(IngestFileStore.class)).isEmpty());
    }
}
