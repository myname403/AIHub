/**
 * 动态路由 API —— AIHub 适配版。
 *
 * 后端 /api/platform/system/permission/info 返回的 data 结构：
 *   { routes: MenuNode[], permissions: string[] }
 * 其中 MenuNode 为平铺树节点：
 *   { id, name, path, component, icon, menuType: "M"|"C"|"F", sort, visible, status, children }
 *   （M=目录 C=菜单 F=按钮；按钮不参与路由，仅用于 v-permission 指令）
 *
 * pure-admin 需要的路由格式：
 *   { path, name?, component: "视图相对路径", meta: { title, icon, rank }, children: [...] }
 * 其中 component 是字符串时，router/utils.ts 的 addAsyncRoutes 会自动映射到
 * src/views 下的同名 .vue 文件（import.meta.glob）。
 */
import Axios from "axios";

type Result = {
  success: boolean;
  data: Array<any>;
};

/** 登录时缓存的后端菜单树（api/user.ts 登录成功后写入） */
let cachedMenuTree: Array<any> = [];

/** 供登录流程写入菜单树 */
export const cacheRoutes = (tree: Array<any>) => {
  cachedMenuTree = tree ?? [];
};

/** Element Plus 图标名 → iconify 格式（HomeFilled → ep/home-filled，与静态路由 ep/xxx 风格一致） */
const toIconify = (icon?: string): string => {
  if (!icon) return "";
  const kebab = icon.replace(/([a-z0-9])([A-Z])/g, "$1-$2").toLowerCase();
  return `ep/${kebab}`;
};

/**
 * 后端 MenuNode → pure-admin 路由对象（递归）。
 * 后端节点为平铺结构：{ id, name, path, component, icon, menuType, sort, visible, children }，
 * 其中 menuType："M" 目录 / "C" 菜单 / "F" 按钮。
 */
const toPureRoute = (node: any): any => {
  const menu = node ?? {};
  const route: any = {
    path: menu.path ?? `/${menu.id}`,
    // component 为字符串，addAsyncRoutes 会映射到 src/views/<component>.vue；
    // 目录节点（M 型）无组件，交给 addAsyncRoutes 置空（仅作菜单层级）
    component: menu.component ?? undefined,
    meta: {
      title: menu.name,
      icon: toIconify(menu.icon),
      rank: menu.sort ?? 99,
      showLink: menu.visible !== 0
    }
  };
  const children = (menu.children ?? [])
    .filter((c: any) => c?.menuType !== "F") // 按钮不进路由
    .map(toPureRoute);
  if (children.length > 0) {
    route.children = children;
  }
  return route;
};

/**
 * 获取动态路由。
 * 优先用登录流程（api/user.ts）缓存的菜单树；页面刷新后内存缓存清空，
 * 此时用当前 token 重新拉取权限树（与登录时同一个接口），保证刷新后菜单不丢。
 */
export const getAsyncRoutes = async (): Promise<Result> => {
  if (!cachedMenuTree.length) {
    const { getToken } = await import("@/utils/auth");
    const token = getToken()?.accessToken;
    if (token) {
      // 走原始 axios（不经 http 拦截器解包），手动取 R.data.routes
      const res = await Axios.get("/api/platform/system/permission/info", {
        headers: { Authorization: `Bearer ${token}` },
        timeout: 10000
      });
      cacheRoutes(res.data?.data?.routes ?? []);
    }
  }
  // 过滤 /welcome：静态路由里已有首页，避免重复注册
  const routes = cachedMenuTree
    .filter(node => node?.path !== "/welcome")
    .map(toPureRoute);
  return { success: true, data: routes };
};
