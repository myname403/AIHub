/**
 * uni CLI 启动器（scripts/uni.js）。
 *
 * 背景：本项目沿用 HBuilderX 目录结构（manifest.json / pages.json 在项目根目录），
 * 而 uni CLI 默认从 src/ 读取源码。官方约定是在进程启动前设置环境变量
 * UNI_INPUT_DIR 指向源码目录 —— 写在 vite.config.js 里来不及生效，
 * 因为 CLI 初始化（读取 manifest.json）发生在加载 vite 配置之前。
 *
 * 本脚本必须在启动 uni CLI 前用绝对路径设置好该变量，所有 npm 命令
 * （dev:h5 / dev:mp-weixin / build:h5 / build:mp-weixin）统一经由这里转发，
 * 命令行参数原样透传（CAC 解析 process.argv.slice(2)）。
 */
const path = require("path");

// 项目根目录（scripts/ 的上一级）；HBuilderX 运行时也使用根目录结构，保持一致
process.env.UNI_INPUT_DIR = path.resolve(__dirname, "..");

require("@dcloudio/vite-plugin-uni/bin/uni.js");
