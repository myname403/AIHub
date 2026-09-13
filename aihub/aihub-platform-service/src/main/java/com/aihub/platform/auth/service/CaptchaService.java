package com.aihub.platform.auth.service;

import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.LineCaptcha;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * 图形验证码服务（注册页防刷）。
 *
 * <p><b>工作流程：</b>
 * <pre>
 * 前端 GET /auth/captcha → 后端生成图片 + 唯一 ID，答案存本地缓存(2分钟)，返回 {id, 图片base64}
 * 前端提交注册时带上 id + 用户输入的答案 → 后端取出比对（比对后立即删除，一次性）
 * </pre>
 *
 * <p><b>为什么存 Caffeine 本地缓存而不是 Redis：</b>验证码是短命数据且单机部署够用，
 * 不引入跨服务依赖；多实例部署时需换 Redis（学习取舍点，见学习文档 07）。
 *
 * <p><b>为什么校验后立即删除：</b>防止同一个验证码反复尝试（重放攻击）。
 *
 * <p><b>aihub.auth.captcha-enabled 开关：</b>自动化测试/内网部署可关闭校验
 * （生成接口仍可用但 verify 直接通过）。生产务必保持默认开启。
 * 详见学习文档《07-RBAC与管理端.md》。
 */
@Service
public class CaptchaService {

    /** 验证码开关：false 时 verify 直接放行（自动化测试/内网部署用） */
    @Value("${aihub.auth.captcha-enabled:true}")
    private boolean captchaEnabled;

    /** 验证码缓存：key=验证码 ID，value=答案。2 分钟未使用自动过期 */
    private final Cache<String, String> captchaCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(2))
            .maximumSize(10_000)
            .build();

    /** 生成结果：id 给前端回传，image 是 base64 的 PNG（前端直接放 <img src="data:image/png;base64,...">） */
    public record CaptchaImage(String id, String imageBase64) {
    }

    /** 生成一张 4 位字符的线段干扰验证码 */
    public CaptchaImage generate() {
        LineCaptcha captcha = CaptchaUtil.createLineCaptcha(120, 40, 4, 10);
        String id = UUID.randomUUID().toString().replace("-", "");
        // getCode() 是答案；只存答案不存图片，图片直接给前端
        captchaCache.put(id, captcha.getCode());
        return new CaptchaImage(id, captcha.getImageBase64Data());
    }

    /**
     * 校验（一次性）：存在且忽略大小写匹配才算通过；无论成败都删除，防重放。
     * captcha-enabled=false 时直接放行（测试便利开关）。
     */
    public boolean verify(String captchaId, String code) {
        if (!captchaEnabled) {
            return true;
        }
        if (captchaId == null || code == null || code.isBlank()) {
            return false;
        }
        String expected = captchaCache.asMap().remove(captchaId);
        return expected != null && expected.equalsIgnoreCase(code.trim());
    }
}
