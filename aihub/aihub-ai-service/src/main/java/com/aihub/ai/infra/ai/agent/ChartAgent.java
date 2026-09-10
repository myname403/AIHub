package com.aihub.ai.infra.ai.agent;

import com.aihub.ai.domain.model.AgentTask;
import com.aihub.ai.domain.spi.ArtifactStore;
import com.aihub.ai.infra.ai.ChatClientFactory;
import org.springframework.stereotype.Component;

/**
 * 图表 Agent（对齐课程 ChartAgent）：把数据生成 ECharts 图表页面。
 */
@Component
public class ChartAgent extends GenerationAgent {

    public ChartAgent(ChatClientFactory chatClientFactory, ArtifactStore artifactStore) {
        super(chatClientFactory, artifactStore);
    }

    @Override
    public String name() {
        return com.aihub.ai.domain.model.AgentNames.CHART;
    }

    @Override
    public String description() {
        return "把数据生成图表（ECharts）页面";
    }

    @Override
    protected String systemPrompt() {
        return """
                你是图表生成 Agent。根据任务内容生成一个完整、独立、可直接打开的 HTML 图表页面。
                要求：包含 <!DOCTYPE html>；通过 <script src="https://cdn.jsdelivr.net/npm/echarts@5/dist/echarts.min.js"></script>
                引入 ECharts；选择最合适的图表类型（柱状/折线/饼图）；数据完全来自任务内容，不得编造；
                页面含标题与图例。只输出 HTML。""";
    }

    @Override
    protected String artifactName(AgentTask task) {
        return "数据图表.html";
    }
}
