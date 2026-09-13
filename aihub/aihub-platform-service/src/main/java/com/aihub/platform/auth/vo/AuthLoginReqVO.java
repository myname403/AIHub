package com.aihub.platform.auth.vo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登录请求 VO（View Object，Web 层入参）。
 *
 * <p><b>VO 与 DO 的分层规则（芋道同款，工程化核心约定）：</b>
 * <ul>
 *   <li>Controller 只收发 VO——入参校验注解写在 VO 上，响应字段按前端需要裁剪；</li>
 *   <li>DO（数据对象）只属于持久层，<b>绝不允许出现在 Controller 签名里</b>
 *       ——否则数据库结构变化会直接波及前端契约（passwordHash 这类敏感字段
 *       也必须靠"分层"而不是"记得手动置空"来保证不出门）；</li>
 *   <li>VO → DO 的转换放 Service 层（字段少直接手写映射，字段多用 BeanUtils 拷贝）。</li>
 * </ul>
 * 详见学习文档《07-RBAC与管理端.md》的 VO/DTO/DO 分层章节。
 */
@Data
public class AuthLoginReqVO {

    /** 租户码（如 "demo"），登录必须显式指定租户 */
    @NotBlank(message = "租户标识不能为空")
    private String tenantCode;

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;

    /** 图形验证码 ID（GET /auth/captcha 返回）。防暴力破解，一次性 */
    private String captchaId;

    /** 用户输入的验证码答案 */
    private String captchaCode;
}
