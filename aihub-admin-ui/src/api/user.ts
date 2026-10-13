import Axios from "axios";
import { http } from "@/utils/http";
import { cacheRoutes } from "@/api/routes";

export type UserResult = {
  success: boolean;
  data: {
    /** 头像 */
    avatar: string;
    /** 用户名 */
    username: string;
    /** 昵称 */
    nickname: string;
    /** 当前登录用户的角色 */
    roles: Array<string>;
    /** 按钮级别权限 */
    permissions: Array<string>;
    /** `token` */
    accessToken: string;
    /** 用于调用刷新`accessToken`的接口时所需的`token` */
    refreshToken: string;
    /** `accessToken`的过期时间（格式'xxxx/xx/xx xx:xx:xx'） */
    expires: Date;
  };
};

export type RefreshTokenResult = {
  success: boolean;
  data: {
    /** `token` */
    accessToken: string;
    /** 用于调用刷新`accessToken`的接口时所需的`token` */
    refreshToken: string;
    /** `accessToken`的过期时间（格式'xxxx/xx/xx xx:xx:xx'） */
    expires: Date;
  };
};

/** 登录参数（AIHub：租户码 + 用户名 + 密码） */
export type LoginParams = {
  tenantCode: string;
  username: string;
  password: string;
};

/** JWT 有效期毫秒数（与后端 aihub.security.jwt-expire-seconds=7200 保持一致） */
const TOKEN_TTL_MS = 7200_000;

/**
 * 登录（对接 AIHub 网关 POST /auth/login）。
 *
 * 流程：
 *  1. 调 /auth/login 拿 token（http 响应拦截器已把 R 解包，这里直接是 data）；
 *  2. 立刻用新 token 调 /api/platform/system/permission/info 拿"动态路由树 + 按钮权限码"；
 *  3. 把路由树缓存给 api/routes.ts 的 getAsyncRoutes 用（pure-admin 初始化路由时会调）；
 *  4. 组装成 pure-admin 期望的 UserResult 结构（roles/permissions/accessToken...）。
 *
 * @param data 登录表单
 */
export const getLogin = async (data?: LoginParams) => {
  // http 拦截器解包后：{ tenantId, userId, token, username }
  const loginData: any = await http.request<any>("post", "/auth/login", {
    data
  });
  const token = loginData?.token;
  if (!token) {
    throw new Error("登录失败：未获取到令牌");
  }

  // 用新 token 拉权限信息（此处手动携带 Authorization，因为 setToken 还没执行）
  const permRes = await Axios.get("/api/platform/system/permission/info", {
    headers: { Authorization: `Bearer ${token}` },
    timeout: 10000
  });
  // 该请求走原始 axios（不经拦截器解包），手动取 R.data
  const permData = permRes.data?.data ?? {
    routes: [],
    permissions: []
  };

  // 缓存路由树，getAsyncRoutes 直接取（避免二次请求）
  cacheRoutes(permData.routes ?? []);

  const result: UserResult = {
    success: true,
    data: {
      avatar: "",
      username: loginData.username,
      nickname: loginData.username,
      roles: ["admin"], // 路由可见性由后端返回的 routes 决定，这里给默认角色
      permissions: permData.permissions ?? [],
      accessToken: token,
      refreshToken: token, // AIHub 暂无独立 refreshToken，复用 accessToken
      expires: new Date(Date.now() + TOKEN_TTL_MS) as any
    }
  };
  return result;
};

/**
 * 刷新 token（AIHub 暂未实现独立刷新，直接返回原 token 并顺延过期时间）。
 *
 * 参数保留但不用（形参命名以 `_` 开头，符合 eslint 的
 * `@typescript-eslint/no-unused-vars` 白名单规则），以免调用方签名不一致。
 */
export const refreshTokenApi = async (_data?: object) => {
  const { getToken } = await import("@/utils/auth");
  const token = getToken()?.accessToken ?? "";
  const result: RefreshTokenResult = {
    success: true,
    data: {
      accessToken: token,
      refreshToken: token,
      expires: new Date(Date.now() + TOKEN_TTL_MS) as any
    }
  };
  return result;
};
