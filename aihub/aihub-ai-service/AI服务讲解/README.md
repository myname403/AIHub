# aihub-ai-service AI服务讲解

> 配套源码：`aihub/aihub-ai-service/src/main/`（约 150 个类，全项目最大模块）
> 定位：产品的**能力核心**（:8082）——模型网关、对话、RAG 知识库、工具调用、MCP 接入、Agent、会话记忆、产物，全在这一个进程里。

## 学习路线

| 轮次 | 文档 | 内容 |
|---|---|---|
| 第一轮 | [01-骨架-DDD四层与为什么大单体.md](./01-骨架-DDD四层与为什么大单体.md) | application/domain/infra/web 各放什么、ArchUnit 卡口、"故意做胖"的三个理由 |
| 第二轮 | [02-三条核心链路精讲.md](./02-三条核心链路精讲.md) | 对话流式链路、知识库入库+检索链路、Agent 任务链路 |
| 第三轮 | [03-记忆卡片与动手实验.md](./03-记忆卡片与动手实验.md) | 口诀卡片 + 5 个实验 + 自测 |
| 深读一 | [04-对话与RAG核心类逐行精讲.md](./04-对话与RAG核心类逐行精讲.md) | 源码级：ChatClientFactory/执行器/Resolver/入库编排/Redis对/Controller/记忆，每段讲"为什么" |
| 深读二 | [05-Agent体系逐行精讲.md](./05-Agent体系逐行精讲.md) | 源码级：BaseAgent/PlanningAgent/GenerationAgent/预算/注册中心/取消链路，"四道缰绳" |

## 一分钟版本

这个模块的类多到吓人，但**每个类只有一道判断题**：

```
它是业务概念（App/Chunk/AgentTask）？        → domain/model
它是"我需要别人提供的能力"（接口）？          → domain/spi（端口）
它是接口的某种具体实现（连DB/连Redis/调模型）？ → infra（适配器）
它是编排上述东西的流程？                     → application
它是 HTTP/WS 入口？                        → web
```

**口诀：domain 定规矩，infra 干活，application 指挥，web 开门。**
