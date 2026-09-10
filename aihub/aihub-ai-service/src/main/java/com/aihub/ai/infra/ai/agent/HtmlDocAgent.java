package com.aihub.ai.infra.ai.agent;

import com.aihub.ai.domain.model.AgentTask;
import com.aihub.ai.domain.spi.ArtifactStore;
import com.aihub.ai.infra.ai.ChatClientFactory;
import org.springframework.stereotype.Component;

/**
 * 网页/文档 Agent（对齐课程 HtmlDocAgent）：生成网页内容类产物，作为默认执行 Agent。
 */
@Component
public class HtmlDocAgent extends GenerationAgent {

    public HtmlDocAgent(ChatClientFactory chatClientFactory, ArtifactStore artifactStore) {
        super(chatClientFactory, artifactStore);
    }

    @Override
    public String name() {
        return com.aihub.ai.domain.model.AgentNames.HTML;
    }

    @Override
    public String description() {
        return "生成网页 / 文档 / 总结类内容页面";
    }

    @Override
    protected String systemPrompt() {
        return """
                你是网页内容生成 Agent。根据任务内容生成一个完整、独立、结构清晰的 HTML 页面。
                要求：包含 <!DOCTYPE html>；含标题、正文段落、必要的列表或小结；
                只基于任务内容（及上下文已有数据）生成，禁止编造外部事实。只输出 HTML。""";
    }

    @Override
    protected String artifactName(AgentTask task) {
        return "内容页面.html";
    }
}
