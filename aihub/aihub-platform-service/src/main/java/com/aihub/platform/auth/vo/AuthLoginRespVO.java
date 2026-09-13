package com.aihub.platform.auth.vo;

import lombok.Data;

/**
 * 登录响应 VO：只包含前端登录后真正需要的字段。
 *
 * <p>替代原先手拼 HashMap 的写法——用类型安全的 VO 后，
 * 字段名拼错在编译期就能发现，Swagger 文档也能自动展示字段说明。
 */
@Data
public class AuthLoginRespVO {

    /** JWT 令牌，前端存储后每次请求带 Authorization: Bearer <token> */
    private String token;

    /** 租户 ID */
    private Long tenantId;

    /** 用户 ID */
    private Long userId;

    /** 用户名 */
    private String username;
}
