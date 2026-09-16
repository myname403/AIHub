package com.aihub.ai.domain.spi;

/**
 * 领域事件发件箱（Outbox）端口 —— 跨服务「最终一致」的兜底通道。
 *
 * <p><b>解决什么问题：</b>AI 服务要把用量（token 消耗、耗时）上报给平台服务，
 * 这是一次跨服务调用。平台服务抖动/超时的那几百毫秒里，如果只是打条日志把数据丢掉，
 * 账目就永久缺失（且没有任何补救途径）。
 * 发件箱把「上报」从"尽力而为"升级为"至少一次"：
 * <b>先把事件落本地库（与业务同一个库，可靠），再由后台投递线程重试直到成功</b>。
 *
 * <p><b>为什么要走这个端口（架构约束）：</b>application 层不允许依赖 infra
 * （ArchUnit 构建期卡关），所以"落库"这个能力必须先在这里定义成接口，
 * 由 infra 提供 {@code DbEventOutbox} 实现（见 aihub-ai-service/infra/outbox）。
 * 同款做法在项目里已用过多次：{@code IngestFileStore}、{@code MetricsRecorder}。
 *
 * <p><b>使用约定：</b>入队失败（数据库不可用等）只记日志、绝不向上抛——
 * 它本身就是"主流程失败后的兜底"，兜底再抛异常会把原始错误盖掉。
 */
public interface EventOutbox {

    /**
     * 落一条待投递事件。
     *
     * @param tenantId  租户（发件箱表带 tenant_id，隔离与其它表一致）
     * @param eventType 事件类型，取值见 {@link com.aihub.ai.domain.model.EventTypes}
     * @param payload   事件载荷（由 infra 序列化为 JSON 落库）
     */
    void enqueue(Long tenantId, String eventType, Object payload);
}
