package com.aihub.platform.user.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * sys_user 表数据对象：平台用户，必须属于唯一租户。
 *
 * <p>映射说明：{@code @TableName} 声明表名；字段驼峰 ↔ 列下划线由
 * MyBatis-Plus 默认映射（passwordHash ↔ password_hash），无需逐个标注。
 * 本表主键未标 @TableId，MP 按"id"字段名自动识别主键。
 *
 * <p><b>安全要点：passwordHash 存的是 BCrypt 哈希</b>（自带随机盐），
 * 永不明文落库或出现在日志中。BCrypt 哈希形如
 * {@code $2a$10$N9qo8uLOickgx2ZMRZoMye...}（算法版本+成本因子+盐+哈希）。
 * 详见学习文档《04-平台服务-aihub-platform-service.md》。
 */
@Data
@TableName("sys_user")
public class SysUserDO {

    /** 主键 */
    private Long id;

    /** 所属租户 —— 多租户隔离核心字段，一切按租户+条件查询 */
    private Long tenantId;

    /** 登录用户名（租户内唯一） */
    private String username;

    /** BCrypt 加密存储，永不明文落库或出现在日志中 */
    private String passwordHash;

    /** 显示昵称 */
    private String nickname;

    /** 1启用 0停用。登录时校验（AuthService 第④步） */
    private Integer status;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
