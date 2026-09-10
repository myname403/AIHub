package com.aihub.ai.infra.ai.agent;

import com.aihub.ai.domain.model.AgentTask;
import com.aihub.ai.domain.spi.ArtifactStore;
import com.aihub.ai.infra.ai.ChatClientFactory;
import org.springframework.stereotype.Component;

/**
 * 表格 Agent（对齐课程 TableAgent）：把结构化数据整理成 HTML 表格页面。
 */
@Component
public class TableAgent extends GenerationAgent {

    public TableAgent(ChatClientFactory chatClientFactory, ArtifactStore artifactStore) {
        super(chatClientFactory, artifactStore);
    }

    @Override
    public String name() {
        return com.aihub.ai.domain.model.AgentNames.TABLE;
    }

    @Override
    public String description() {
        return "把结构化数据整理成 HTML 表格页面";
    }

    @Override
    protected String systemPrompt() {
        return """
                你是表格生成 Agent。根据任务内容生成一个完整、独立、可直接打开的 HTML 表格页面。
                要求：包含 <!DOCTYPE html>；表格结构清晰、含表头；使用简洁内联样式美化；
                数据必须完全来自任务内容，不得编造。只输出 HTML。""";
    }

    @Override
    protected String artifactName(AgentTask task) {
        return "数据表格.html";
    }
}
