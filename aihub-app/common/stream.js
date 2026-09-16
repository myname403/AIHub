import config from './config.js'
import { getToken } from './request.js'

/**
 * 流式对话（NDJSON Chunked 双端统一封装）。
 *
 * - H5：fetch + ReadableStream（不用 EventSource：无法携带自定义 Header）
 * - 小程序：uni.request({ enableChunked: true }) + onChunkReceived
 *
 * 两端均按 \n 切分事件帧并处理半包，回调 onEvent(事件帧对象)。
 * 事件类型：msg.start / token / rag.sources / agent.step / artifact / msg.end / error
 */
export function streamChat({ appId = 0, conversationId = '', message, scene, modelCode = '' }, onEvent) {
  // modelCode 可选：前端模型切换器指定时后端优使用该模型，空 = 走管理端场景路由
  const body = JSON.stringify({
    appId,
    conversationId,
    message,
    scene,
    ...(modelCode ? { modelCode } : {})
  })
  const header = {
    'Content-Type': 'application/json',
    Authorization: 'Bearer ' + getToken()
  }
  let buffer = ''
  // H5 侧的中断句柄（AbortController 只能在 H5 用；小程序侧用 task.abort()）
  let h5Controller = null

  const emitLine = (line) => {
    const trimmed = line.trim()
    if (!trimmed) return
    try {
      onEvent(JSON.parse(trimmed))
    } catch (e) {
      // 忽略无法解析的残缺行
    }
  }

  const handleChunk = (text) => {
    buffer += text
    const lines = buffer.split('\n')
    buffer = lines.pop() || '' // 最后一段可能是半包，留到下一块
    for (const line of lines) emitLine(line)
  }

  /**
   * 流结束时冲刷缓冲区：服务端最后一帧可能没有以 \n 结尾，
   * 只靠 handleChunk 会把它当"半包"留在 buffer 里被丢掉（表现为最后一条消息渲染不出来）。
   */
  const flush = () => {
    const rest = buffer
    buffer = ''
    emitLine(rest)
  }

  // #ifdef H5
  h5Controller = new AbortController()
  fetch(config.baseUrl + '/api/ai/chat/ndjson', {
    method: 'POST',
    headers: header,
    body,
    signal: h5Controller.signal
  }).then(async (res) => {
    if (res.status === 401) {
      onEvent({ e: 'error', data: { message: '登录已过期' } })
      return
    }
    const reader = res.body.getReader()
    const decoder = new TextDecoder('utf-8')
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      handleChunk(decoder.decode(value, { stream: true }))
    }
    flush()
  }).catch((err) => {
    // 用户点"停止"触发的中断不是错误，不弹提示
    if (err && err.name === 'AbortError') return
    onEvent({ e: 'error', data: { message: '连接失败：' + err.message } })
  })
  // #endif

  // #ifdef MP-WEIXIN
  const task = uni.request({
    url: config.baseUrl + '/api/ai/chat/ndjson',
    method: 'POST',
    header,
    data: body,
    enableChunked: true,
    responseType: 'arraybuffer',
    success: () => flush(),
    fail: (err) => onEvent({ e: 'error', data: { message: '连接失败：' + (err.errMsg || '') } })
  })
  task.onChunkReceived((res) => {
    if (res && res.data) {
      handleChunk(arrayBufferToUtf8(res.data))
    }
  })
  // #endif

  return { abort }

  function abort() {
    // #ifdef H5
    // 真正断开 fetch 连接（旧实现里 H5 分支是空的，只能等服务端跑完）。
    // 注意：客户端断开只是停止接收，服务端仍在执行 —— 所以调用方还要配合
    // POST /api/ai/agent/cancel 让服务端在步骤边界终止（见 pages/chat/chat.vue 的 stop()）。
    if (h5Controller) h5Controller.abort()
    // #endif
    // #ifdef MP-WEIXIN
    task && task.abort()
    // #endif
  }
}

/**
 * 微信小程序没有 TextDecoder，这里手写 UTF-8 解码（含中文多字节）。
 */
function arrayBufferToUtf8(buf) {
  const bytes = new Uint8Array(buf)
  let out = ''
  let i = 0
  while (i < bytes.length) {
    const b = bytes[i]
    if (b < 0x80) {
      out += String.fromCharCode(b)
      i += 1
    } else if (b < 0xe0) {
      out += String.fromCharCode(((b & 0x1f) << 6) | (bytes[i + 1] & 0x3f))
      i += 2
    } else if (b < 0xf0) {
      out += String.fromCharCode(
        ((b & 0x0f) << 12) | ((bytes[i + 1] & 0x3f) << 6) | (bytes[i + 2] & 0x3f)
      )
      i += 3
    } else {
      const cp = ((b & 0x07) << 18) | ((bytes[i + 1] & 0x3f) << 12) |
        ((bytes[i + 2] & 0x3f) << 6) | (bytes[i + 3] & 0x3f)
      i += 4
      out += String.fromCodePoint(cp)
    }
  }
  return out
}
