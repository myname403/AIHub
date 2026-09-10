package com.aihub.mcp.api;

import com.aihub.mcp.model.AppBrief;
import com.aihub.mcp.model.ChatAnswer;
import com.aihub.mcp.model.ChunkHit;
import com.aihub.mcp.model.KbBrief;

import java.util.List;

/**
 * AIHub 能力端口（MCP 工具的唯一出口）。
 *
 * <p>抽象成接口而非直接用 RestClient，是为了让 {@code AiHubTools} 可以脱离网络单测——
 * 工具的价值在「参数校验 + 结果组织」，那部分不该依赖能不能连上网关。
 */
public interface AiHubApi {

    List<AppBrief> listApplications();

    List<KbBrief> listKnowledgeBases();

    List<ChunkHit> searchKnowledge(Long kbId, String query, Integer topK);

    ChatAnswer chat(Long appId, String message, String scene);
}
