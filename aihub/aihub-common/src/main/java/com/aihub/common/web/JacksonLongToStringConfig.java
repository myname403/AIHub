package com.aihub.common.web;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigInteger;

/**
 * 全局 Jackson 定制：把 Long / long / BigInteger 序列化成 JSON 字符串。
 *
 * <p><b>为什么必须这么做（雪花 ID 精度丢失）：</b>本项目所有实体主键都用雪花算法生成，
 * 是 19 位十进制的 {@code Long}（约 2.1×10¹⁸）。而前端 JavaScript 的 {@code Number}
 * 底层是 IEEE-754 双精度浮点，只能精确表示到 2⁵³-1（约 9.007×10¹⁵）；一旦后端把这种
 * 大整数以 JSON <b>数字</b>下发，浏览器解析时末几位会被四舍五入抹平
 * （如真实 id {@code ...992123} 变成 {@code ...992000}）。前端再把它回传给后端做
 * "路由指向模型 / 编辑 / 删除 / 绑定"等操作时，就出现"id 对不上、不属于当前租户"这类
 * 隐蔽故障——数据本身没错，是数字在 JS 里"装不下"了。
 *
 * <p><b>解法：</b>统一把长整型序列化为字符串，前端拿到的是精确的 {@code "...992123"}，
 * 原样回传即可（后端 {@code @RequestBody Long}/{@code @PathVariable Long} 能正常反序列化字符串）。
 *
 * <p><b>作用范围：</b>本类位于 {@code com.aihub.common}，被 {@code scanBasePackages="com.aihub"}
 * 的 AI 服务、平台服务加载；网关是 WebFlux 且只扫 {@code com.aihub.gateway}，不受影响。
 * 定制只作用于 Spring MVC 响应序列化的 ObjectMapper，各服务内部 {@code new ObjectMapper()}
 * 的自用的序列化（如 NDJSON 事件帧、发件箱落库）不受影响，仍保持数字。
 */
@Configuration
public class JacksonLongToStringConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
        return builder -> {
            // 包装类型 Long 与基本类型 long 都转字符串，覆盖实体 id 及数量字段
            builder.serializerByType(Long.class, ToStringSerializer.instance);
            builder.serializerByType(Long.TYPE, ToStringSerializer.instance);
            // BigInteger 同样是任意长度整数，一并处理，避免个别统计/游标字段踩坑
            builder.serializerByType(BigInteger.class, ToStringSerializer.instance);
        };
    }
}
