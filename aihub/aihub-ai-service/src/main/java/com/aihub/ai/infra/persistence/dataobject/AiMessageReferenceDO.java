package com.aihub.ai.infra.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ai_message_reference 表数据对象：回答的引用来源（RAG 溯源）。
 */
@Data
@TableName("ai_message_reference")
public class AiMessageReferenceDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;
    /** 所属回答消息 */
    private Long messageId;
    private String convKey;
    /** 引用序号，对应正文中的 [n] */
    private Integer seq;
    private Long kbId;
    private Long docId;
    private String docName;
    private Double score;
    /** 命中分片原文 */
    private String content;
    private LocalDateTime createTime;
}
