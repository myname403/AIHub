package com.aihub.ai.infra.ai.agent;

import com.aihub.ai.domain.model.AgentResult;
import com.aihub.ai.domain.model.AgentTask;
import com.aihub.ai.domain.model.ArtifactInfo;
import com.aihub.ai.domain.spi.ArtifactStore;
import com.aihub.ai.domain.spi.StreamSink;
import com.aihub.ai.infra.ai.ChatClientFactory;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 内容生成类 Agent 基类（对齐课程 TableAgent / ChartAgent / HtmlDocAgent 的公共逻辑）。
 *
 * <p>统一职责：LLM 生成完整 HTML → 产物落盘 → 广播 artifact 事件 → 返回摘要。
 */
@Slf4j
public abstract class GenerationAgent extends BaseAgent {

    protected final ArtifactStore artifactStore;

    protected GenerationAgent(ChatClientFactory chatClientFactory, ArtifactStore artifactStore) {
        super(chatClientFactory);
        this.artifactStore = artifactStore;
    }

    /** 子类提供系统提示词（决定生成什么形态的页面） */
    protected abstract String systemPrompt();

    protected abstract String artifactName(AgentTask task);

    @Override
    public AgentResult execute(AgentTask task, StreamSink sink) {
        int[] index = {0};
        try {
            step(sink, index, "think", "准备生成：" + artifactName(task));
            String system = systemPrompt();
            String user = """
                    请根据以下任务内容生成结果，只输出完整 HTML，不要任何解释或代码块标记。

                    任务：%s""".formatted(task.goal());

            String html = llm(task.tenantId(), system, user);
            html = stripCodeFence(html);
            if (html.isBlank() || !html.contains("<")) {
                return new AgentResult(AgentResult.FAILED, "内容生成失败，请检查模型配置", List.of());
            }

            ArtifactInfo artifact = artifactStore.save(
                    task.tenantId(), null, artifactName(task), "text/html",
                    html.getBytes(StandardCharsets.UTF_8));

            step(sink, index, "observe", "产物已生成：" + artifact.previewUrl());
            if (sink != null) {
                sink.emit(com.aihub.ai.domain.model.StreamEvent.of(0, com.aihub.ai.domain.model.StreamEvent.ARTIFACT,
                        java.util.Map.of("name", artifact.name(),
                                "url", artifact.previewUrl())));
            }
            String answer = "已生成「%s」，点击查看：%s".formatted(artifact.name(), artifact.previewUrl());
            return AgentResult.done(answer, List.of(artifact));
        } catch (Exception e) {
            return fallback(task, e);
        }
    }

    /** 去掉 LLM 常见的 ```html 代码围栏 */
    private String stripCodeFence(String text) {
        String t = text.trim();
        if (t.startsWith("```")) {
            int first = t.indexOf('\n');
            int last = t.lastIndexOf("```");
            if (first > 0 && last > first) {
                return t.substring(first + 1, last).trim();
            }
        }
        return t;
    }
}
