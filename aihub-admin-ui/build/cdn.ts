import { Plugin as importToCDN } from "vite-plugin-cdn-import";

/**
 * @description 打包时采用`cdn`模式，仅限外网使用（默认不采用，如果需要采用cdn模式，请在 .env.production 文件，将 VITE_CDN 设置成true）
 * 平台采用国内cdn：https://www.bootcdn.cn，当然你也可以选择 https://unpkg.com 或者 https://www.jsdelivr.com
 * 注意：上面提到的仅限外网使用也不是完全肯定的，如果你们公司内网部署的有相关js、css文件，也可以将下面配置对应改一下，整一套内网版cdn
 *
 * ★ 为什么这里导出的是「工厂函数」而不是「插件对象」：
 *   importToCDN(...) 在**被调用的那一刻**就会解析 modules 里每个包的 package.json（用来取版本号）。
 *   原先写成模块级常量 `export const cdn = importToCDN({...})`，意味着
 *   **只要 import 本文件就会触发解析** —— 而 build/plugins.ts 是无条件 import 的，
 *   于是即使 .env.production 里 VITE_CDN=false，构建也会直接失败：
 *     Error: modules: vue-demi package.json file does not exist
 *   （当时 vue-demi 解析不到，是因为 .npmrc 的 shamefully-hoist 在 pnpm 10+ 失效、
 *     node_modules 退化成严格布局；该问题已在 pnpm-workspace.yaml 里开启 shamefullyHoist 修复。
 *     但惰性构造本身仍是必要的：CDN 关闭时就不该去解析 CDN 依赖。）
 *   改成惰性构造后，只有真正启用 CDN 模式才会做解析。
 */
export const cdn = () =>
  importToCDN({
    //（prodUrl解释： name: 对应下面modules的name，version: 自动读取本地package.json中dependencies依赖中对应包的版本号，path: 对应下面modules的path，当然也可写完整路径，会替换prodUrl）
    prodUrl: "https://cdn.bootcdn.net/ajax/libs/{name}/{version}/{path}",
    modules: [
      {
        name: "vue",
        var: "Vue",
        path: "vue.global.prod.min.js"
      },
      {
        name: "vue-router",
        var: "VueRouter",
        path: "vue-router.global.min.js"
      },
      // 项目中没有直接安装vue-demi，但是pinia用到了，所以需要在引入pinia前引入vue-demi（https://github.com/vuejs/pinia/blob/v2/packages/pinia/package.json#L77）
      {
        name: "vue-demi",
        var: "VueDemi",
        path: "index.iife.min.js"
      },
      {
        name: "pinia",
        var: "Pinia",
        path: "pinia.iife.min.js"
      },
      {
        name: "element-plus",
        var: "ElementPlus",
        path: "index.full.min.js",
        css: "index.min.css"
      },
      {
        name: "axios",
        var: "axios",
        path: "axios.min.js"
      },
      {
        name: "dayjs",
        var: "dayjs",
        path: "dayjs.min.js"
      },
      {
        name: "echarts",
        var: "echarts",
        path: "echarts.min.js"
      }
    ]
  });
