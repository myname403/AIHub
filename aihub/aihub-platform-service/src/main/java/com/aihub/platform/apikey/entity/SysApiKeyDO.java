package com.aihub.platform.apikey.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * sys_api_key 表数据对象（DO = Data Object）：开放 API 密钥，一行 = 一把 Key。
 *
 * <p><b>MyBatis-Plus 注解解释（DO 类的通用模式）：</b>
 * <ul>
 *   <li>{@code @TableName("sys_api_key")}：类 ↔ 表的映射（驼峰类名默认映射下划线表名，
 *       显式写出更清晰）；</li>
 *   <li>{@code @TableId(type = IdType.ASSIGN_ID)}：主键，ASSIGN_ID = 插入时由框架自动生成
 *       雪花 ID（64 位分布式唯一 ID，时间戳+机器号+序列，趋势递增、不依赖数据库自增，
 *       适合分库分表）；</li>
 *   <li>{@code @TableLogic}：逻辑删除标记。此后 MP 的 deleteById 实际执行
 *       {@code UPDATE deleted=1}，所有查询自动追加 {@code WHERE deleted=0}
 *       —— 数据不物理删除，可审计可恢复（"软删"）；</li>
 *   <li>{@code @Data}（Lombok）：getter/setter/toString/equals/hashCode。</li>
 * </ul>
 *
 * <p>命名规范：类名带 DO 后缀（区别于 domain 模型），字段名驼峰 ↔ 数据库列下划线
 * （MP 默认 camelToUnderline 映射，无需 @TableField）。
 *
 * <p>安全约束：{@code keyHash} 是 HMAC-SHA256 结果，<b>绝不存明文</b>。
 * 详见学习文档《04-平台服务-aihub-platform-service.md》。
 */
@Data
@TableName("sys_api_key")
public class SysApiKeyDO {

    /** 主键（雪花 ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属租户 —— 多租户隔离字段，所有查询必须带 */
    private Long tenantId;

    /** 绑定的应用（AI 服务侧），为空表示 Key 可访问该租户下全部应用 */
    private Long appId;

    /** 密钥名称（用户起的备注名，如 "数据分析脚本"） */
    private String name;

    /** 密钥哈希（HMAC-SHA256 hex）。校验时对请求明文重算哈希后精确匹配此列 */
    private String keyHash;

    /** 明文前缀，仅用于展示辨识（如 "ak_9f3c"，控制台列表显示用） */
    private String keyPrefix;

    /** 1启用 0禁用。verify 时校验，停用立即失效（缓存场景最长延迟 60 秒） */
    private Integer status;

    /** 过期时间；null 表示长期有效 */
    private LocalDateTime expireAt;

    /** 最后一次使用时间（touch() 方法更新，供控制台展示） */
    private LocalDateTime lastUsedAt;

    /** 累计使用次数 */
    private Long usedCount;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 逻辑删除标记：0=未删 1=已删（@TableLogic 自动维护） */
    @TableLogic
    private Integer deleted;
}
