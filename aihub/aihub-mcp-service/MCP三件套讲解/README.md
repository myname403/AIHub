# MCP 三件套讲解文档

> 配套源码：`aihub-mcp-service`（业务核心）+ `aihub-mcp-sse`（在线传输）+ `aihub-mcp-stdio`（本地传输）
> 一句话定位：把 AIHub 的能力（列应用/查知识库/提问）包装成 **MCP 工具**，
> 让 Claude Desktop、IDE 插件等任意 MCP 客户端都能"调用 AIHub"。

## 学习路线（两轮）

| 轮次 | 文档 | 内容 |
|---|---|---|
| 第一轮 | [01-架构与逐类精讲.md](./01-架构与逐类精讲.md) | 三件套为什么是三个模块、16 个类逐个讲、四大设计决策 |
| 第二轮 | [02-记忆卡片与动手实验.md](./02-记忆卡片与动手实验.md) | 口诀卡片 + MCP Inspector 实测 + stdio 手工喂 JSON |

## 一分钟版本

三件套 = **一个后厨 + 两个取餐口**：

```
                 aihub-mcp-service（后厨：AiHubTools 四道菜 + HTTP 客户端）
                 ▲                          ▲
     aihub-mcp-sse（堂食窗口）      aihub-mcp-stdio（外卖窗口）
     HTTP+SSE，常驻 8090，          stdin/stdout，被宿主进程
     远程客户端连进来                java -jar 拉起，本机一对一
```

核心思想一句话：**传输与业务解耦——工具实现只写一次，换传输只换一个协议 starter。**
