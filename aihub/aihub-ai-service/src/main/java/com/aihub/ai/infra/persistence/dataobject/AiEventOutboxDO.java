package com.aihub.ai.infra.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ai_event_outbox 表数据对象：领域事件发件箱。
 *
 * <p>status 语义：0 待投递 / 1 已投递 / 2 已放弃（重试次数超上限）。
 */
@Data
@TableName("ai_event_outbox")
public class AiEventOutboxDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;
    /** 事件类型，取值见 com.aihub.ai.domain.model.EventTypes */
    private String eventType;
    /** 事件载荷（JSON 字符串，投递时反序列化成原对象） */
    private String payloadJson;
    private Integer status;
    private Integer retryCount;
    private LocalDateTime createTime;
    private LocalDateTime publishedAt;
}
