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

  const handleChunk = (text) => {
    buffer += text
    const lines = buffer.split('\n')
    buffer = lines.pop() || '' // 最后一段可能是半包，留到下一块
    for (const line of lines) {
      const trimmed = line.trim()
      if (!trimmed) continue
      try {
        onEvent(JSON.parse(trimmed))
      } catch (e) {
        // 忽略无法解析的残缺行
      }
    }
  }

  // #ifdef H5
  fetch(config.baseUrl + '/api/ai/chat/ndjson', {
    method: 'POST',
    headers: header,
    body
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
    handleChunk('')
  }).catch((err) => {
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
    success: () => handleChunk(''),
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
