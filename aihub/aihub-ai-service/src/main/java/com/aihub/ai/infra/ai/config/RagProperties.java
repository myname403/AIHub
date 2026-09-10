package com.aihub.ai.infra.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 检索参数中「调用时读取」的部分（aihub.rag.top-k / similarity-threshold）。
 *
 * <p>热更新机制与 {@link AgentProperties} 相同：Nacos 配置变更触发
 * EnvironmentChangeEvent，ConfigurationPropertiesRebinder 整体重绑定，
 * 下一次检索即用新值。注意 chunk-size / chunk-overlap / ingest-max-retry
 * 属于应用层（KnowledgeAppService）的参数，受架构规则约束不能 import infra
 * 的配置类，仍以 {@code @Value} 形式存在于应用层（改后需重启）。
 *
 * <p>与 {@code @Value} 相比还有一个隐性收益：键名集中、有类型校验，
 * 配错单位（如把阈值写成百分数 60）时启动即暴露，而不是运行时才异常。
 */
@ConfigurationProperties(prefix = "aihub.rag")
public class RagProperties {

    /** 每个知识库检索返回的相似片段数（top-k） */
    private int topK = 4;

    /** 相似度阈值，低于该分数的片段不进入模型上下文（0~1） */
    private double similarityThreshold = 0.6;

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public double getSimilarityThreshold() {
        return similarityThreshold;
    }

    public void setSimilarityThreshold(double similarityThreshold) {
        this.similarityThreshold = similarityThreshold;
    }
}
