package com.aihub.ai.domain.spi;

/**
 * Agent 中断注册表（M4 DoD：用户点击停止后，服务端后续步骤真正取消）。
 *
 * <p>按会话维度登记取消信号，Agent 在每一步执行前检查；
 * 单实例部署用内存实现即可，多实例时替换为 Redis 实现（本接口不变）。
 */
public interface AgentCancelRegistry {

    /** 登记取消信号（前端点"停止"时调用） */
    void cancel(String conversationId);

    /** 该会话是否被请求取消 */
    boolean isCanceled(String conversationId);

    /** 任务结束时清理，避免长期驻留 */
    void clear(String conversationId);
}
