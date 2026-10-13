# aihub-mcp-stdio 传输模块讲解

> 配套源码：`aihub-mcp-stdio/`（全项目最小模块：**1 个 29 行的启动类 + 2 个配置文件**）
> 定位：MCP 三件套里的"**外卖窗口**"——本地方式，JSON-RPC 走 stdin/stdout，被宿主进程（Claude Desktop / IDE 插件）拉起。
> 姊妹文档：`aihub-mcp-sse/SSE传输讲解/`（堂食窗口），两套对照读效果最佳。

## 学习路线

| 文档 | 内容 |
|---|---|
| [01-逐类与配置逐段精讲.md](./01-逐类与配置逐段精讲.md) | pom、启动类、application.yml、logback-spring.xml 逐段——"stdout 即协议"的全部工程后果 |
| [02-记忆卡片与动手实验.md](./02-记忆卡片与动手实验.md) | 卡片 + 手工喂 JSON-RPC + 复现"一连上就崩" |

## 一分钟版本

模块虽小，却是**"一个认知决定一串配置"**的最佳教材：
stdio 传输里 **stdout 不是日志出口，而是 JSON-RPC 协议通道**。
由此引发连锁反应：banner 必须关、日志必须改道 stderr+文件、root 级别降 WARN、
不能有任何 Web 容器、进程生命周期交给宿主——五个决定全部源自这一句话。
