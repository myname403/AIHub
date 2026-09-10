import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

// 本项目沿用 HBuilderX 目录结构（pages.json 在项目根目录），
// 而 uni CLI 默认从 src/ 读源码，这里显式把输入目录指回项目根，
// 使 npm run dev:h5 与 HBuilderX 运行共用同一套目录。
process.env.UNI_INPUT_DIR = process.cwd()

export default defineConfig({
  plugins: [uni()]
})
