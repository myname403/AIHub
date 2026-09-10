package com.aihub.ai.web;

import com.aihub.common.result.R;
import com.aihub.common.tenant.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * M0 冒烟接口：验证租户上下文已正确传递到 AI 服务。
 * M1 之后可删除或保留为健康检查。
 */
@RestController
@RequestMapping("/api/ai")
public class SmokeController {

    @GetMapping("/smoke")
    public R<Map<String, Object>> smoke() {
        Map<String, Object> data = new HashMap<>();
        // 从上下文读取，而非请求参数——租户来源唯一可信
        data.put("tenantId", TenantContext.getTenantId());
        data.put("userId", TenantContext.getUserId());
        data.put("service", "aihub-ai-service");
        return R.ok(data);
    }
}
