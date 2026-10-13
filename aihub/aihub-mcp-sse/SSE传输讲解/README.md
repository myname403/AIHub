# aihub-mcp-sse 传输模块讲解

> 配套源码：`aihub-mcp-sse/`（1 个启动类 + 3 个门禁类 + 1 份 yml + 2 个测试）
> 定位：MCP 三件套里的"**堂食窗口**"——在线方式，HTTP+SSE 传输，供远程 MCP 客户端接入。
> 姊妹文档：`aihub-mcp-stdio/stdio传输讲解/`（外卖窗口），两套对照读效果最佳。

## 学习路线

| 文档 | 内容 |
|---|---|
| [01-逐类与配置逐段精讲.md](./01-逐类与配置逐段精讲.md) | pom、启动类、application.yml 逐段、通道门禁三件套、测试 |
| [02-记忆卡片与动手实验.md](./02-记忆卡片与动手实验.md) | 卡片 + SSE 协议手工实验 + 自测 |

## 一分钟版本

这个模块自己**没有任何业务代码**——业务全在 aihub-mcp-service。它只干三件事：
1. 换上传输依赖（`spring-ai-starter-mcp-server-webmvc`，自带 Servlet 容器）；
2. 启动类把组件扫描指到上一层 `com.aihub.mcp`（把后厨的装配捞进来）；
3. 加了一道可选的"通道门禁"（Bearer token），为跨机暴露做准备。
