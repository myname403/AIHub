import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

// 本项目沿用 HBuilderX 目录结构（pages.json 在项目根目录），而 uni CLI 默认从 src/ 读源码。
// npm 命令由 scripts/uni.js 启动器在 CLI 初始化前设置 UNI_INPUT_DIR 指向项目根；
// 这里再兜底设置一次（Vite 配置阶段，保证 vite 自身阶段变量已生效）。
process.env.UNI_INPUT_DIR = process.env.UNI_INPUT_DIR || process.cwd()

export default defineConfig({
  plugins: [uni()]
})
