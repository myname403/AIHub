package com.aihub.ai.domain.spi;

import com.aihub.ai.domain.model.AgentResult;
import com.aihub.ai.domain.model.AgentTask;

/**
 * Agent SPI（★ 扩展点，对齐课程 MyManus 的 Agent 抽象）。
 *
 * <p>同一接口多实现：planning（规划拆解）/ table / chart / html / browser...
 * 通过 AgentRegistry 按名查找与派发，策略可配置、可插拔。
 */
public interface Agent {

    /** Agent 唯一标识（对齐课程 AgentFactory 的按名查找） */
    String name();

    /** 描述（供规划 Agent 选择子 Agent 时参考） */
    default String description() {
        return name();
    }

    /** 执行任务，过程事件经 sink 回传（agent.step / artifact） */
    AgentResult execute(AgentTask task, StreamSink sink);
}
