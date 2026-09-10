package com.aihub.ai.infra.persistence.do_;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * ai_model_provider 表数据对象（平台级供应商字典，无 tenant_id、无逻辑删）。
 */
@Data
@TableName("ai_model_provider")
public class AiModelProviderDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** openai / volcengine / dashscope / deepseek / ollama */
    private String code;
    private String name;
    private String baseUrl;
    /** 额外配置（JSON 字符串，如自定义 header） */
    private String configJson;
}
