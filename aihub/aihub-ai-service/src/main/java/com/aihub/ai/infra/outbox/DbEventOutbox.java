package com.aihub.ai.infra.outbox;

import com.aihub.ai.domain.spi.EventOutbox;
import com.aihub.ai.infra.persistence.mapper.AiEventOutboxMapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 发件箱写入实现（{@link EventOutbox} 端口的 infra 实现）。
 *
 * <p>把事件序列化成 JSON 落到 {@code ai_event_outbox}（status=0 待投递），
 * 由 {@link EventOutboxDispatcher} 后台重投。
 *
 * <p><b>失败处理：只记日志，绝不向上抛。</b>调用方是在"上报已经失败"的兜底分支里调用本类的，
 * 如果这里再抛异常，会把原始错误挤出堆栈（排查时只看得到一个"落库失败"的次生异常）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DbEventOutbox implements EventOutbox {

    private final AiEventOutboxMapper outboxMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void enqueue(Long tenantId, String eventType, Object payload) {
        if (tenantId == null || eventType == null || payload == null) {
            log.warn("发件箱入队参数不完整，已跳过 tenant={} type={}", tenantId, eventType);
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(payload);
            outboxMapper.insertEvent(IdWorker.getId(), tenantId, eventType, json);
            log.info("事件已落入发件箱（待后台重投）tenant={} type={}", tenantId, eventType);
        } catch (Exception e) {
            // 兜底的兜底：这里再失败就只能丢事件了，但绝不能影响主流程
            log.error("事件落发件箱失败（已放弃本次兜底）tenant={} type={} err={}",
                    tenantId, eventType, e.getMessage());
        }
    }
}
