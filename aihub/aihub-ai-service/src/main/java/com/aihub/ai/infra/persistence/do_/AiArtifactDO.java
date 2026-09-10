package com.aihub.ai.infra.persistence.do_;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * ai_artifact 表数据对象（Agent 生成的表格 / 图表 / HTML 产物）。
 */
@Data
@TableName("ai_artifact")
public class AiArtifactDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;
    private Long messageId;
    private Long taskId;
    private String name;
    private String filePath;
    private String mime;
    private Long size;
    private String previewUrl;

    @TableLogic
    private Integer deleted;
}
