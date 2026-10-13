# mock/ 目录说明（模板遗留，当前**未启用**）

这三个文件是 **vue-pure-admin 模板自带的 mock 数据**，不是本项目的接口定义：

| 文件              | 对接的路径              | 与本项目的关系                                                 |
| ----------------- | ----------------------- | -------------------------------------------------------------- |
| `login.ts`        | `POST /login`           | ❌ 本项目真实登录是 `POST /auth/login`（body 带 `tenantCode`） |
| `asyncRoutes.ts`  | `GET /get-async-routes` | ❌ 本项目动态路由是 `GET /api/platform/system/permission/info` |
| `refreshToken.ts` | `POST /refresh-token`   | ⚠️ 路径同名，但本项目走的是平台服务的真实实现                  |

## 为什么留着而不是删掉

1. 它们**已经不再生效**：`build/plugins.ts` 里已移除 `vitePluginFakeServer` 的注册
   （原配置还带着 `enableProd: true`，会把 mock 打进生产包，是个隐患）。
2. 删掉属于**不可逆**操作，而这些文件在 `aihub-admin-ui` 这个仓库里是上游模板的原始文件，
   留着不影响构建；写这份说明足以避免后来者误判"接口是假数据"。
3. 只是 `package.json` 里的 `vite-plugin-fake-server` 依赖暂时还挂着
   —— 移除它会与 `pnpm-lock.yaml` 不一致，导致 Dockerfile 里的
   `pnpm install --frozen-lockfile` 失败。要清理的话请一并执行：

   ```bash
   pnpm remove vite-plugin-fake-server   # 同时更新 lockfile
   rm -rf mock/
   ```

## 想临时 mock 接口怎么办

优先直接对着真实后端跑（`vite.config.ts` 已把 `/auth`、`/api` 代理到 `http://localhost:8080`）。
确需 mock 时再单独接一个方案（`vite-plugin-mock`、MSW 或本地 json-server），
**不要再按模板的 `/login` 那套写**，否则路径对不上、调试时浪费大量时间。
