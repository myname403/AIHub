# aihub-common 公共模块讲解

> 配套源码：`aihub/aihub-common/src/main/`（13 个类，6 个包）
> 定位：全项目所有服务**共用的"规矩工具箱"**——它不含任何业务，但每个业务类都在用它。

## 学习路线（两轮）

| 轮次 | 文档 | 内容 |
|---|---|---|
| 第一轮 | [01-逐类精讲.md](./01-逐类精讲.md) | 6 个包 13 个类，每个：干什么 → 为什么这么设计 → 不用它会怎样 |
| 第二轮 | [02-记忆卡片与动手实验.md](./02-记忆卡片与动手实验.md) | 口诀 + 卡片 + 3 个 curl/SQL 实验 |

## 一分钟版本

如果把整个项目比作一个小区，aihub-common 就是**物业总规**：
- 谁家客人来了怎么登记（R 统一响应、ResultCode 错误码）；
- 违规了怎么开罚单（BizException + GlobalExceptionHandler）；
- 每户的门牌号怎么管（TenantContext 租户上下文）；
- 快递单号怎么编（TraceContext 链路追踪）；
- 保险柜怎么锁（ApiKeyCodec、AesGcmTextCipher、JwtVerifier）；
- 大件物品怎么搬（JacksonLongToStringConfig 解决雪花 ID 精度丢失）。

**判断一个类该不该放 common 的标准就一条：两个以上服务都要用、且与业务无关。**
