package com.aihub.ai.infra.ai.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 内置工具示例（对齐课程 Tool Calling：@Tool 注解声明式定义）。
 *
 * <p>模型在对话中会自主决定是否调用。真实业务工具（查课程、下单等）
 * 按同样方式编写，或通过 MCP 接入外部工具。
 */
public class TimeTools {

    @Tool(description = "获取当前的系统日期时间，格式 yyyy-MM-dd HH:mm:ss")
    public String currentTime() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    @Tool(description = "计算两个日期之间相差的天数，日期格式 yyyy-MM-dd")
    public String daysBetween(@ToolParam(description = "起始日期") String start,
                              @ToolParam(description = "结束日期") String end) {
        try {
            var d1 = java.time.LocalDate.parse(start);
            var d2 = java.time.LocalDate.parse(end);
            return "相差 " + java.time.temporal.ChronoUnit.DAYS.between(d1, d2) + " 天";
        } catch (Exception e) {
            return "日期格式错误，请使用 yyyy-MM-dd";
        }
    }
}
