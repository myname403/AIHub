package com.aihub.ai.infra.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * ai_model 表数据对象。api_key_enc 为 AES-GCM 密文（Base64 存储）。
 */
@Data
@TableName("ai_model")
public class AiModelDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;
    private String providerCode;
    private String modelCode;
    /** AES-GCM 密文（Base64），解密由 infra-ai 完成 */
    private String apiKeyEnc;
    private String baseUrl;
    private Integer vectorDim;
    private Integer isDefault;
    private Integer status;

    @TableLogic
    private Integer deleted;
}
