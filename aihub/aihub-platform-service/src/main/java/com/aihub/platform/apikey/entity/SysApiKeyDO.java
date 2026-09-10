package com.aihub.platform.apikey.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * sys_api_key 表数据对象：开放 API 密钥。
 *
 * <p>安全约束：{@code keyHash} 是 HMAC-SHA256 结果，<b>绝不存明文</b>。
 */
@Data
@TableName("sys_api_key")
public class SysApiKeyDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;
    /** 绑定的应用（AI 服务侧），为空表示 Key 可访问该租户下全部应用 */
    private Long appId;
    private String name;
    /** 密钥哈希（HMAC-SHA256 hex） */
    private String keyHash;
    /** 明文前缀，仅用于展示辨识 */
    private String keyPrefix;
    /** 1启用 0禁用 */
    private Integer status;
    private LocalDateTime expireAt;
    private LocalDateTime lastUsedAt;
    private Long usedCount;
    private LocalDateTime createTime;

    @TableLogic
    private Integer deleted;
}
