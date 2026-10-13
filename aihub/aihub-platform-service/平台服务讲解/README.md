# aihub-platform-service 平台服务讲解

> 配套源码：`aihub/aihub-platform-service/src/main/`（约 65 个类，7 个业务包）
> 定位：全系统的**户口本 + 发证机关 + 计费闸门**——租户、用户、RBAC、API Key、配额用量都在这。
> 它是你学"一个像样的 SpringBoot 业务服务长什么样"的最佳样本。

## 学习路线（三轮）

| 轮次 | 文档 | 内容 |
|---|---|---|
| 第一轮 | [01-骨架与包地图.md](./01-骨架与包地图.md) | 7 个包各自管什么、10 张表怎么分家、一个包一个"人设" |
| 第二轮 | [02-核心链路精讲.md](./02-核心链路精讲.md) | 登录/注册/RBAC 权限/配额扣减 四条链路逐帧讲 |
| 第三轮 | [03-记忆卡片与动手实验.md](./03-记忆卡片与动手实验.md) | 口诀卡片 + SQL/curl 实验 + 自测 |

## 一分钟版本

```
aihub-platform-service（:8081，MVC + MyBatis-Plus + Flyway）
├── auth      发证窗口：登录/注册/验证码        → 发 JWT
├── tenant/user  户口本：租户表、用户表
├── system    家规：RBAC 角色/菜单/权限（用户-角色-菜单三张关系表）
├── apikey    门禁卡房：签发/吊销开放 API Key
├── quota     计费闸门：配额策略/计数器/流水 + 用量看板
├── internal  后门（内网）：给 AI 服务 Feign 调的 /internal/**
└── config/security  水电煤：拦截器注册、BCrypt、JWT 签发器、MP 插件
```

**判断类归属的口诀**：管"你是谁"找 auth/user，管"你能干啥"找 system，
管"机器凭证"找 apikey，管"你用了多少"找 quota，管"服务间说话"找 internal。
