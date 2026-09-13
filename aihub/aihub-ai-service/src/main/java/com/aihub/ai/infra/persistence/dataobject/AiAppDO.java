package com.aihub.ai.infra.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ai_app 表数据对象。
 * <p>领域层不能携带 MyBatis-Plus 注解，因此 DO 与领域实体分离，由仓储实现做映射。
 */
@Data
@TableName("ai_app")
public class AiAppDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;
    private String name;
    private String systemPrompt;
    private Long modelRouteId;
    private String agentStrategy;
    private String memoryPolicy;
    private Double temperature;
    private Integer status;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
