package com.aihub.ai.web;

import com.aihub.ai.domain.spi.AgentCancelRegistry;
import com.aihub.common.result.R;
import com.aihub.common.tenant.TenantContext;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Agent 控制接口（M4）。
 *
 * <p>取消信号按会话维度登记：前端点击"停止"时调用本接口，
 * 服务端的 Agent 循环会在下一个子任务前检查并真正终止（而不是仅断开连接）。
 */
@Slf4j
@RestController
@RequestMapping("/api/ai/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentCancelRegistry cancelRegistry;

    @PostMapping("/cancel")
    public R<Map<String, Object>> cancel(@RequestBody CancelRequest request) {
        TenantContext.requireTenantId();
        if (request.getConversationId() == null || request.getConversationId().isBlank()) {
            return R.ok(Map.of("canceled", false, "reason", "缺少 conversationId"));
        }
        cancelRegistry.cancel(request.getConversationId());
        Map<String, Object> data = new HashMap<>();
        data.put("conversationId", request.getConversationId());
        data.put("canceled", true);
        return R.ok(data);
    }

    @Data
    public static class CancelRequest {
        /** 会话 ID：与发起 Agent 任务时使用的 conversationId 一致 */
        private String conversationId;
    }
}
