import { cdn } from "./cdn";
import vue from "@vitejs/plugin-vue";
import { viteBuildInfo } from "./info";
import svgLoader from "vite-svg-loader";
import Icons from "unplugin-icons/vite";
import type { PluginOption } from "vite";
import vueJsx from "@vitejs/plugin-vue-jsx";
import tailwindcss from "@tailwindcss/vite";
import { configCompressPlugin } from "./compress";
import removeNoMatch from "vite-plugin-router-warn";
import { visualizer } from "rollup-plugin-visualizer";
import { codeInspectorPlugin } from "code-inspector-plugin";

export function getPluginsList(
  VITE_CDN: boolean,
  VITE_COMPRESSION: ViteCompression
): PluginOption[] {
  const lifecycle = process.env.npm_lifecycle_event;
  return [
    tailwindcss(),
    vue(),
    // jsx、tsx语法支持
    vueJsx(),
    /**
     * 在页面上按住组合键时，鼠标在页面移动即会在 DOM 上出现遮罩层并显示相关信息，点击一下将自动打开 IDE 并将光标定位到元素对应的代码位置
     * Mac 默认组合键 Option + Shift
     * Windows 默认组合键 Alt + Shift
     * 更多用法看 https://inspector.fe-dev.cn/guide/start.html
     */
    codeInspectorPlugin({
      bundler: "vite",
      hideConsole: true
    }),
    viteBuildInfo(),
    /**
     * 开发环境下移除非必要的vue-router动态路由警告No match found for location with path
     * 非必要具体看 https://github.com/vuejs/router/issues/521 和 https://github.com/vuejs/router/issues/359
     * vite-plugin-router-warn只在开发环境下启用，只处理vue-router文件并且只在服务启动或重启时运行一次，性能消耗可忽略不计
     */
    removeNoMatch(),
    /**
     * 已移除 vite-plugin-fake-server（原模板自带的 mock 支持）。
     * 原因：mock/ 下三个文件对接的是模板自己的 /login、/get-async-routes，
     * 与本项目真实后端（/auth/login、/api/platform/system/permission/info）对不上，
     * 而 enableProd: true 还会把 mock 打进生产包——留着只会让后来者误以为"接口是假数据"。
     * 需要临时 mock 时再按官方文档单独接。
     */
    // svg组件化支持
    svgLoader(),
    // 自动按需加载图标
    Icons({
      compiler: "vue3",
      scale: 1
    }),
    // cdn 是工厂函数：只有真正开启 CDN 模式时才构造插件（否则会在配置加载期解析
    // vue-demi 等传递依赖的 package.json 并直接报错，详见 build/cdn.ts 的注释）
    VITE_CDN ? cdn() : null,
    configCompressPlugin(VITE_COMPRESSION),
    // 线上环境删除 console —— 已停用，原因见下（不是随手关的）
    //
    // 现象：`vite build` 直接失败，报
    //   src/assets/iconfont/iconfont.js (23:21): Expression expected
    // 根因：iconfont.js 是 iconfont.cn 生成的注入脚本，里面有
    //   } catch (t) { console && console.log(t); }
    // 这种「带前置判断」的写法。vite-plugin-remove-console 定位语法时会把调用抠掉，
    // 留下 `console && ;` → 语法错误（已用 node --check 验证该文件本身完全合法，
    // 且停掉本插件后构建即通过）。
    //
    // 试过但无效：按官方 README 传 `external: ["src/assets/iconfont/iconfont.js"]`
    // （模板原本就是这么写的），以及改成与路径分隔符无关的 `external: ["iconfont.js"]`
    // —— 两次都没能拦住（该选项在 Windows 的路径形式下匹配不上，与包管理器无关）。
    //
    // 取舍：管理后台是内部系统，生产包保留 console 语句可以接受；
    // 真要移除，建议改用 Vite 内置的 esbuild AST 级方案（它在构建期解析语法，不会抠坏代码）：
    //   vite.config.ts 里加 `esbuild: { drop: ["console", "debugger"] }`
    // 注意 esbuild 的 drop 会移除**所有级别**的 console（含 error/warn），与原来只删 log 不同。
    // 如需恢复本插件：先补回 `import removeConsole from "vite-plugin-remove-console";`，
    // 再解开下一行的注释，并**先确认 iconfont.js 不再被改写**（这是当初无法构建的原因）。
    // removeConsole({ external: ["src/assets/iconfont/iconfont.js"] }),
    // 打包分析
    lifecycle === "report"
      ? visualizer({ open: true, brotliSize: true, filename: "report.html" })
      : (null as any)
  ];
}
