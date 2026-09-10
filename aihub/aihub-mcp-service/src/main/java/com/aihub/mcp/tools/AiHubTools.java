package com.aihub.mcp.tools;

import com.aihub.mcp.api.AiHubApi;
import com.aihub.mcp.api.AiHubCallException;
import com.aihub.mcp.config.AiHubMcpProperties;
import com.aihub.mcp.model.AppBrief;
import com.aihub.mcp.model.ChatAnswer;
import com.aihub.mcp.model.ChunkHit;
import com.aihub.mcp.model.KbBrief;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

/**
 * 暴露给 MCP 客户端的工具集。
 *
 * <h3>三个刻意的设计选择</h3>
 *
 * <p><b>① 全部返回 String，不返回对象。</b>
 * MCP 工具的结果要能被任意客户端（Claude Desktop、IDE 插件、自研 Agent）消费，
 * 纯文本是最不会出错的表示。返回 POJO 依赖两端 JSON Schema 生成实现一致，
 * 跨实现时容易踩坑。
 *
 * <p><b>② 错误转成文字而不是抛异常。</b>
 * {@link AiHubCallException} 在这里被吃掉并整理成人话。
 * 抛出去会变成 JSON-RPC error，模型的反应通常是直接放弃；
 * 而「鉴权未通过，请检查 API Key」这类提示能让它继续推理或如实汇报。
 *
 * <p><b>③ 工具描述写得像给人看的说明书。</b>
 * 描述是模型选择工具的唯一依据。含糊的描述会让模型在「查知识库」和「问 AIHub」
 * 之间反复试错，浪费 token 和时间。
 */
public class AiHubTools {

    private final AiHubApi api;
    private final AiHubMcpProperties properties;

    public AiHubTools(AiHubApi api, AiHubMcpProperties properties) {
        this.api = api;
        this.properties = properties;
    }

    @Tool(name = "aihub_list_applications",
            description = """
                    列出 AIHub 中可用的 AI 应用（助手）。每个应用有自己的系统提示词与能力范围，
                    返回值里的 id 就是后续调用 aihub_ask 时该填的 appId。
                    不确定该用哪个应用时，先调这个工具。""")
    public String listApplications() {
        try {
            List<AppBrief> apps = api.listApplications();
            if (apps.isEmpty()) {
                return "当前租户下没有任何应用。请先在 AIHub 管理后台创建应用。";
            }
            StringBuilder sb = new StringBuilder("可用应用共 ").append(apps.size()).append(" 个：\n");
            for (AppBrief app : apps) {
                sb.append("- id=").append(app.getId())
                        .append("，名称：").append(app.getName());
                if (app.getAgentStrategy() != null && !"none".equals(app.getAgentStrategy())) {
                    sb.append("，Agent 策略：").append(app.getAgentStrategy());
                }
                if (!app.enabled()) {
                    sb.append("（已停用，调用会失败）");
                }
                sb.append('\n');
            }
            return sb.toString();
        } catch (AiHubCallException e) {
            return "查询应用列表失败：" + e.getMessage();
        }
    }

    @Tool(name = "aihub_list_knowledge_bases",
            description = """
                    列出 AIHub 中可用的知识库及其 ID。
                    如果返回了多个知识库而你无法确定该查哪一个，先把这份列表交给用户确认，
                    不要凭猜测随便挑一个——检索错知识库会得到看似合理但完全无关的答案。""")
    public String listKnowledgeBases() {
        try {
            List<KbBrief> kbs = api.listKnowledgeBases();
            if (kbs.isEmpty()) {
                return "当前租户下没有知识库。请先在 AIHub 知识库管理页上传文档。";
            }
            StringBuilder sb = new StringBuilder("可用知识库共 ").append(kbs.size()).append(" 个：\n");
            for (KbBrief kb : kbs) {
                sb.append("- id=").append(kb.getId())
                        .append("，名称：").append(kb.getName());
                if (!kb.enabled()) {
                    sb.append("（已停用）");
                }
                sb.append('\n');
            }
            return sb.toString();
        } catch (AiHubCallException e) {
            return "查询知识库列表失败：" + e.getMessage();
        }
    }

    @Tool(name = "aihub_search_knowledge",
            description = """
                    在 AIHub 知识库中做语义检索，返回最相关的文档片段（含来源文档名与相似度）。
                    适用于「查资料 / 找依据 / 引用原文」这类需求，比让模型自己回忆可靠得多。
                    返回的每条结果都带相似度百分比：低于 60% 通常意味着知识库里没有相关内容，
                    此时应如实告知用户「知识库中未找到相关内容」，不要强行作答。""")
    public String searchKnowledge(
            @ToolParam(description = "知识库 ID，可先用 aihub_list_knowledge_bases 获取")
            Long kbId,
            @ToolParam(description = "检索问题或关键词，用自然语言即可")
            String query,
            @ToolParam(description = "返回条数，默认 5，最多 20", required = false)
            Integer topK) {

        if (query == null || query.isBlank()) {
            return "检索失败：query 不能为空。";
        }
        int limit = topK == null || topK <= 0 ? properties.getDefaultTopK() : Math.min(topK, 20);
        try {
            List<ChunkHit> hits = api.searchKnowledge(kbId, query, limit);
            if (hits.isEmpty()) {
                return "知识库中没有检索到与「" + query + "」相关的内容。";
            }
            StringBuilder sb = new StringBuilder("检索到 ")
                    .append(hits.size()).append(" 条相关内容：\n");
            int index = 1;
            for (ChunkHit hit : hits) {
                sb.append("\n[").append(index++).append("] 来源：")
                        .append(hit.getDocName() == null ? "未知文档" : hit.getDocName())
                        .append("（相似度 ").append(hit.scorePercent()).append("%）\n")
                        .append(truncate(hit.getContent())).append('\n');
            }
            return sb.toString();
        } catch (AiHubCallException e) {
            return "检索失败：" + e.getMessage();
        }
    }

    @Tool(name = "aihub_ask",
            description = """
                    向 AIHub 的某个应用提问，拿回完整回答（内部已包含该应用的提示词、
                    会话记忆，以及它绑定的知识库检索）。适合「需要 AIHub 侧既有配置才能回答」的问题。
                    注意：这是同步调用，会等你把话说完才返回，长回答耗时较久；
                    如果只是要查资料，用 aihub_search_knowledge 更直接。""")
    public String ask(
            @ToolParam(description = "要提问的内容")
            String message,
            @ToolParam(description = "应用 ID；不填则用默认应用", required = false)
            Long appId,
            @ToolParam(description = "场景：chat 普通对话 / rag 走知识库 / agent 任务拆解",
                    required = false)
            String scene) {

        if (message == null || message.isBlank()) {
            return "调用失败：message 不能为空。";
        }
        Long targetApp = appId == null ? properties.getDefaultAppId() : appId;
        try {
            ChatAnswer answer = api.chat(targetApp, message, scene);
            StringBuilder sb = new StringBuilder();
            if (answer.getContent() == null || answer.getContent().isBlank()) {
                return "AIHub 返回了空回答。可能原因：模型未配置、配额已用尽，或该应用已停用。";
            }
            sb.append(answer.getContent());
            if (answer.getConversationId() != null) {
                // 带上会话 ID，用户若要追问可以复用同一会话（记忆不丢）
                sb.append("\n\n---\n会话 ID：").append(answer.getConversationId())
                        .append("（如需继续追问，可告知我复用该会话）");
            }
            return sb.toString();
        } catch (AiHubCallException e) {
            return "调用 AIHub 失败：" + e.getMessage();
        }
    }

    /**
     * 截断超长分片。
     *
     * <p>一次工具调用把几万字塞进上下文，可能直接把预算打满、后续对话全部被挤掉；
     * 宁可截断并标注，也不要静默地撑爆上下文。
     */
    private String truncate(String content) {
        if (content == null) {
            return "（空内容）";
        }
        int max = properties.getChunkMaxChars();
        if (max <= 0 || content.length() <= max) {
            return content;
        }
        return content.substring(0, max) + "…（内容过长已截断，共 " + content.length() + " 字）";
    }
}
