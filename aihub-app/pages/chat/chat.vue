<!--
  对话页 —— 用户端前端的核心页面（学习建议：对照 common/stream.js 一起读）。

  页面职责与数据流：
    ① send()：校验输入 → 消息列表 push 用户气泡和空的 AI 气泡 → streamChat() 发起流式请求
    ② onEvent()：streamChat 收到每个事件帧回调到这里，按事件类型更新 AI 气泡
       - token 事件：assistant.content += 内容 → 气泡随生成逐字变长（打字机效果）
       - rag.sources：填充引用来源卡片
       - agent.step：Agent 模式下展示执行步骤时间线
       - artifact：展示产物预览入口（表格/网页等）
       - msg.end：结束，记录所用模型与耗时（气泡底部小标签）
       - error：把错误信息写进气泡并复位 streaming 状态
    ③ stop()：abort() 断开 HTTP 流 + POST /agent/cancel 通知服务端终止（两步缺一不可，
       客户端断开只是不收了，Agent 服务端还会继续跑完当前子任务）

  三个场景（scene）：chat 普通对话 / rag 走知识库检索 / agent 多步任务，
  同一个接口 /api/ai/chat/ndjson，scene 参数不同后端走不同编排。
-->
<template>
  <view class="chat">
    <scroll-view class="messages" scroll-y :scroll-top="scrollTop" scroll-with-animation>
      <!-- 欢迎卡片 -->
      <view class="welcome-card">
        <text class="wc-icon">✨</text>
        <text class="wc-title">你好，我是 AIHub 智能助手</text>
        <text class="wc-item">· 对话：普通聊天</text>
        <text class="wc-item">· 知识库：基于知识库回答（带引用）</text>
        <text class="wc-item">· Agent：自动拆解任务并生成图表/表格/网页</text>
      </view>

      <view v-for="(msg, i) in messages" :key="i" class="msg" :class="msg.role">
        <template v-if="msg.role === 'user'">
          <view class="bubble user-bubble"><text>{{ msg.content }}</text></view>
          <view class="avatar user-avatar">我</view>
        </template>
        <template v-else>
          <view class="avatar ai-avatar">AI</view>
          <view class="bubble" :class="{ 'error-bubble': msg.isError }">
            <!-- 推理模型的思考过程：卡片式可折叠，回答开始自动收起 -->
            <view v-if="msg.reasoning" class="reasoning">
              <view class="reasoning-head" @click="msg.reasoningOpen = !msg.reasoningOpen">
                <view class="r-pulse" :class="{ running: streaming && !msg.content }">
                  <view class="rp-dot"></view>
                  <view class="rp-dot"></view>
                  <view class="rp-dot"></view>
                </view>
                <text class="r-title">{{ reasoningTitle(msg) }}</text>
                <text class="r-arrow">{{ msg.reasoningOpen ? '▾' : '▸' }}</text>
              </view>
              <view v-if="msg.reasoningOpen" class="reasoning-body">
                <text class="reasoning-text">{{ msg.reasoning }}</text>
              </view>
            </view>

            <!-- Agent 执行过程卡片（对齐 ChatGPT/Manus）：计划清单 + 步骤/工具混合时间线，
                 执行中自动展开、完成后收起为一行摘要，点击可再展开 -->
            <view v-if="msg.trace" class="trace" :class="'t-' + msg.trace.status">
              <view class="trace-head" @click="msg.trace.expanded = !msg.trace.expanded">
                <view v-if="msg.trace.status === 'running'" class="t-spinner"></view>
                <text v-else class="t-state">{{ traceStateMark(msg.trace) }}</text>
                <text class="t-title">{{ traceTitle(msg.trace) }}</text>
                <text class="t-arrow">{{ msg.trace.expanded ? '▾' : '▸' }}</text>
              </view>
              <view v-if="msg.trace.expanded" class="trace-body">
                <!-- 计划清单：agent.plan 下发，agent.task.done 逐项打勾 -->
                <view v-if="msg.trace.plan.length" class="plan-list">
                  <view v-for="(p, pi) in msg.trace.plan" :key="'p' + pi" class="plan-item">
                    <text class="plan-mark" :class="'pm-' + planState(msg.trace, pi)">{{
                      planState(msg.trace, pi) === 'done' ? '✓' : (planState(msg.trace, pi) === 'fail' ? '✗' : '·')
                    }}</text>
                    <view class="plan-text">
                      <text class="plan-agent">{{ p.agent }}</text>
                      <text class="plan-task">{{ p.task }}</text>
                    </view>
                  </view>
                </view>
                <!-- 步骤流：agent.step 与工具卡片按到达顺序交织 -->
                <view v-for="(s, si) in msg.trace.steps" :key="'s' + si" class="trace-item">
                  <template v-if="s.kind === 'step'">
                    <text class="ti-icon" :class="'ti-' + s.type">{{ stepIcon(s.type) }}</text>
                    <text v-if="s.agent" class="ti-agent">{{ s.agent }}</text>
                    <text class="ti-content">{{ s.content }}</text>
                  </template>
                  <template v-else>
                    <view class="tool-cell">
                      <view class="tool-line" @click="s.open = !s.open">
                        <text class="ti-icon" :class="'ti-' + s.state">{{ stepIcon(s.state === 'running' ? 'act' : s.state) }}</text>
                        <text class="tool-name">{{ s.name }}</text>
                        <text v-if="s.source" class="tool-src">{{ s.source }}</text>
                        <text class="tool-meta">{{ toolMeta(s) }}</text>
                        <text v-if="s.state !== 'running'" class="t-arrow sm">{{ s.open ? '▾' : '▸' }}</text>
                      </view>
                      <view v-if="s.open && s.state !== 'running'" class="tool-detail">
                        <view v-if="s.input" class="tool-row">
                          <text class="tool-k">入参</text>
                          <text class="tool-v">{{ s.input }}</text>
                        </view>
                        <view v-if="s.output" class="tool-row">
                          <text class="tool-k">{{ s.state === 'ok' ? '结果' : '错误' }}</text>
                          <text class="tool-v">{{ s.output }}</text>
                        </view>
                      </view>
                    </view>
                  </template>
                </view>
              </view>
            </view>

            <!-- AI 回答按 Markdown 分块渲染 -->
            <block v-for="(b, bi) in mdBlocks(msg.content)" :key="bi">
              <view v-if="b.type === 'p'" class="md-p"><rich-text :nodes="b.nodes"></rich-text></view>
              <view v-else-if="b.type === 'h'" class="md-h"><rich-text :nodes="b.nodes"></rich-text></view>
              <view v-else-if="b.type === 'ul'" class="md-list">
                <view v-for="(it, ii) in b.items" :key="ii" class="md-li">
                  <text class="md-li-mark">•</text>
                  <view class="md-li-content"><rich-text :nodes="it"></rich-text></view>
                </view>
              </view>
              <view v-else-if="b.type === 'ol'" class="md-list">
                <view v-for="(it, ii) in b.items" :key="ii" class="md-li">
                  <text class="md-li-mark">{{ ii + 1 }}.</text>
                  <view class="md-li-content"><rich-text :nodes="it"></rich-text></view>
                </view>
              </view>
              <view v-else-if="b.type === 'code'" class="md-codeblock">
                <text class="md-code-text">{{ b.text }}</text>
              </view>
              <view v-else-if="b.type === 'hr'" class="md-hr"></view>
            </block>
            <!-- 流式生成光标 -->
            <text v-if="streaming && i === messages.length - 1" class="typing-cursor">▍</text>

            <view v-if="msg.sources && msg.sources.length" class="sources">
              <view class="sources-title">
                <text class="st-text">📚 参考来源 · {{ msg.sources.length }} 条</text>
              </view>
              <view v-for="(s, j) in msg.sources" :key="j" class="source-item">
                <text class="source-idx">{{ s.index }}</text>
                <text class="source-name">{{ s.docName }}</text>
                <text class="source-score">{{ (s.score * 100).toFixed(0) }}%</text>
              </view>
            </view>
            <view v-if="msg.artifacts && msg.artifacts.length" class="artifacts">
              <text
                v-for="(a, j) in msg.artifacts"
                :key="j"
                class="artifact"
                @click="openArtifact(a)">{{ a.name }} ↗</text>
            </view>
            <!-- 失败重试：把上一条用户消息原样重发（复用 startAsk，不动历史） -->
            <view v-if="msg.isError && !streaming" class="retry-bar" @click="retry">
              <text class="retry-btn">↻ 重试</text>
            </view>
            <!-- 回答完成显示所用模型与总耗时（后端 msg.end 带出，Agent 链路同样生效） -->
            <text v-if="(msg.model || msg.costMs) && !streaming" class="model-tag">{{ modelTag(msg) }}</text>
          </view>
        </template>
      </view>

      <view v-if="streaming" class="thinking">
        <view class="avatar ai-avatar">AI</view>
        <view class="bubble thinking-bubble">
          <view class="dots">
            <view class="dot d1"></view>
            <view class="dot d2"></view>
            <view class="dot d3"></view>
          </view>
          <text class="thinking-text">{{ statusText }}</text>
        </view>
      </view>
      <view class="bottom-space"></view>
    </scroll-view>

    <view class="dock">
      <view class="dock-top">
        <text class="kb-link" @click="goKb">📚 知识库</text>
        <picker
          mode="selector"
          :range="modelNames"
          :value="modelIndex"
          @change="onModelChange">
          <view class="model-picker">
            <text class="mp-label">⚡ {{ modelNames[modelIndex] || '默认模型' }}</text>
            <text class="arrow">▾</text>
          </view>
        </picker>
        <text v-if="modelCode" class="clear-model" @click="clearModel">默认</text>
        <text class="kb-link" @click="goAdmin">⚙️ 管理</text>
      </view>

      <view class="toolbar">
        <view
          v-for="s in scenes"
          :key="s.value"
          class="scene"
          :class="{ active: scene === s.value }"
          @click="scene = s.value">
          <text>{{ s.label }}</text>
        </view>
      </view>

      <view class="input-bar">
        <input
          v-model="input"
          class="input"
          confirm-type="send"
          placeholder="请输入您的问题…"
          placeholder-class="ph"
          @confirm="send" />
        <button v-if="!streaming" class="send" @click="send">发送</button>
        <button v-else class="send stop" @click="stop">停止</button>
      </view>
    </view>
  </view>
</template>

<script>
import config from '../../common/config.js'
import { streamChat } from '../../common/stream.js'
import { post, get, getToken, handleUnauthorized } from '../../common/request.js'

const WELCOME = { role: 'assistant', content: '你好，我是 AIHub 智能助手。可以选择下方模式提问：\n· 对话：普通聊天\n· 知识库：基于知识库回答（带引用）\n· Agent：自动拆解任务并生成图表/表格/网页' }

/**
 * 轻量 Markdown 渲染（不引第三方库，H5 / 微信小程序双端一致）：
 * 块级（段落/标题/有序无序列表/代码块/分割线）解析成结构化块交给模板渲染，
 * 行内（**加粗** / *斜体* / `行内代码`）解析成 rich-text nodes。
 * 流式阶段每个 token 全量重解析——单条回答通常 <10KB，开销可忽略。
 */
function parseInline(text) {
  const nodes = []
  const re = /\*\*[^*]+\*\*|`[^`]+`|\*[^*\s][^*]*\*/g
  let last = 0
  let m
  while ((m = re.exec(text)) !== null) {
    if (m.index > last) nodes.push({ type: 'text', text: text.slice(last, m.index) })
    const t = m[0]
    if (t.startsWith('**')) {
      nodes.push({ name: 'span', attrs: { class: 'md-b' }, children: [{ type: 'text', text: t.slice(2, -2) }] })
    } else if (t.startsWith('`')) {
      nodes.push({ name: 'span', attrs: { class: 'md-inline-code' }, children: [{ type: 'text', text: t.slice(1, -1) }] })
    } else {
      nodes.push({ name: 'span', attrs: { class: 'md-i' }, children: [{ type: 'text', text: t.slice(1, -1) }] })
    }
    last = m.index + t.length
  }
  if (last < text.length) nodes.push({ type: 'text', text: text.slice(last) })
  return nodes.length ? nodes : [{ type: 'text', text: '' }]
}

function parseBlocks(md) {
  const lines = String(md || '').split('\n')
  const blocks = []
  let inCode = false
  let codeLines = []
  let listType = null
  let listItems = []
  const flushList = () => {
    if (listItems.length) {
      blocks.push({ type: listType === 'ol' ? 'ol' : 'ul', items: listItems })
      listItems = []
      listType = null
    }
  }
  const flushCode = () => {
    if (codeLines.length) {
      blocks.push({ type: 'code', text: codeLines.join('\n') })
      codeLines = []
    }
  }
  for (const raw of lines) {
    const line = raw.replace(/\s+$/, '')
    if (line.trim().startsWith('```')) {
      if (inCode) {
        flushCode()
        inCode = false
      } else {
        flushList()
        inCode = true
      }
      continue
    }
    if (inCode) {
      codeLines.push(raw)
      continue
    }
    if (!line.trim()) {
      flushList()
      continue
    }
    const h = line.match(/^(#{1,4})\s+(.*)/)
    if (h) {
      flushList()
      blocks.push({ type: 'h', level: h[1].length, nodes: parseInline(h[2]) })
      continue
    }
    if (/^\s*(-{3,}|\*{3,})\s*$/.test(line)) {
      flushList()
      blocks.push({ type: 'hr' })
      continue
    }
    const ul = line.match(/^\s*[-*•]\s+(.*)/)
    if (ul) {
      if (listType === 'ol') flushList()
      listType = 'ul'
      listItems.push(parseInline(ul[1]))
      continue
    }
    const ol = line.match(/^\s*\d+[.、)]\s+(.*)/)
    if (ol) {
      if (listType === 'ul') flushList()
      listType = 'ol'
      listItems.push(parseInline(ol[1]))
      continue
    }
    flushList()
    blocks.push({ type: 'p', nodes: parseInline(line) })
  }
  flushList()
  flushCode()
  return blocks
}

export default {
  data() {
    return {
      messages: [WELCOME],
      input: '',
      scene: 'chat',
      conversationId: '',
      streaming: false,
      statusText: '',
      scrollTop: 0,
      currentTask: null,
      // 模型切换：models=后端启用的模型列表，modelCode 空串 = 走管理端场景路由
      models: [],
      modelCode: '',
      scenes: [
        { label: '对话', value: 'chat' },
        { label: '知识库', value: 'rag' },
        { label: 'Agent', value: 'agent' }
      ]
    }
  },
  computed: {
    modelNames() {
      return ['默认（场景路由）', ...this.models.map(m => m.modelCode)]
    },
    modelIndex() {
      const i = this.models.findIndex(m => m.modelCode === this.modelCode)
      return i < 0 ? 0 : i + 1
    }
  },
  onLoad() {
    this.loadModels()
  },
  methods: {
    /** 拉取当前租户启用的模型列表（模型管理页配置的） */
    async loadModels() {
      try {
        const list = await get('/api/ai/model')
        this.models = (list || []).filter(m => m.status === 1)
      } catch (e) {
        // 拉取失败不打断聊天：保持默认路由
        this.models = []
      }
    },
    onModelChange(e) {
      const i = Number(e.detail.value)
      this.modelCode = i === 0 ? '' : this.models[i - 1].modelCode
    },
    clearModel() {
      this.modelCode = ''
    },
    goKb() {
      uni.navigateTo({ url: '/pages/kb/kb' })
    },
    goAdmin() {
      uni.navigateTo({ url: '/pages/admin/index' })
    },
    send() {
      const text = (this.input || '').trim()
      if (!text || this.streaming) return
      this.input = ''
      this.messages.push({ role: 'user', content: text })
      this.startAsk(text)
    },
    /** 失败重试：取最近一条用户消息原样重发（复用 startAsk，不动历史消息） */
    retry() {
      if (this.streaming) return
      let text = ''
      for (let i = this.messages.length - 1; i >= 0; i--) {
        if (this.messages[i].role === 'user') {
          text = this.messages[i].content
          break
        }
      }
      if (!text) return
      // 弹掉失败气泡（以及可能残留的中间态消息），回到该用户消息的位置再重新提问
      while (this.messages.length && this.messages[this.messages.length - 1].role !== 'user') {
        this.messages.pop()
      }
      this.startAsk(text)
    },
    /**
     * 发起一次问答（send 与 retry 共用入口）。
     * 关键：必须从响应式数组里取回“代理引用”再交给 onEvent 修改。
     * 若直接用 push 前的原始对象，改它绕过 Vue3 的 Proxy set 陷阱→不触发重渲染→
     * 每条 token 都不刷新，直到 msg.end 改 streaming 才整体重渲染，表现为“一次性吐出、不流式”。
     */
    startAsk(text) {
      this.messages.push({
        role: 'assistant',
        content: '',
        reasoning: '',
        reasoningOpen: true,
        sources: [],
        artifacts: [],
        // trace：Agent 执行卡片状态机（懒创建——纯对话/知识库问答没有 agent 事件，不出空卡片）
        trace: null,
        // seqSeen：已处理的最大事件序号（index 去重容错，防乱序/重放帧重复渲染）
        seqSeen: -1
      })
      const assistant = this.messages[this.messages.length - 1]
      this.streaming = true
      this.statusText = '思考中…'
      this.scrollToBottom()

      this.currentTask = streamChat(
        {
          appId: Number(uni.getStorageSync('appId') || 9001),
          conversationId: this.conversationId,
          message: text,
          scene: this.scene,
          modelCode: this.modelCode
        },
        (event) => this.onEvent(assistant, event)
      )
    },
    stop() {
      // 关键：先通知服务端取消，否则后端 Agent 循环还会把剩余子任务跑完
      if (this.conversationId) {
        post('/api/ai/agent/cancel', { conversationId: this.conversationId }).catch(() => {})
      }
      if (this.currentTask) {
        this.currentTask.abort()
      }
      this.streaming = false
      this.statusText = '已停止'
      // trace 终态交给 __transport.abort 事件统一处理（finalizeTrace 幂等，重复调用无副作用）
    },
    onEvent(assistant, event) {
      const type = event.e || event.event
      // 传输层兜底事件没有业务序号，走独立的终态处理
      if (type === '__transport.end' || type === '__transport.timeout' || type === '__transport.abort') {
        this.onTransportEvent(assistant, type)
        return
      }
      // index 去重容错：后端 SequencedSink 保证序号全局单调递增，收到 <= 已见序号的帧
      // 说明是乱序重放/重复投递（网关重试等），直接丢弃——否则 token 会重复拼接、卡片重复渲染
      const seq = typeof event.i === 'number' ? event.i : (typeof event.index === 'number' ? event.index : null)
      if (seq !== null) {
        const seen = assistant.seqSeen == null ? -1 : assistant.seqSeen
        if (seq <= seen) return
        assistant.seqSeen = seq
      }
      const data = event.data || {}
      switch (type) {
        case 'msg.start':
          if (data.conversationId) this.conversationId = data.conversationId
          break
        case 'token':
          assistant.content += data.content || ''
          // 回答一开始就收起思考卡片（推理帧可能先于 token 全部到达，光靠 reasoning 分支收不了）
          if (assistant.content && assistant.reasoningOpen) assistant.reasoningOpen = false
          // Agent 链路的答案是执行完毕后一次性补发的：token 一到就收起执行卡片
          if (assistant.trace && assistant.trace.status === 'running') this.finalizeTrace(assistant, 'done')
          this.scrollToBottom()
          break
        case 'reasoning':
          // 思考增量：累加到 reasoning 区；一旦正式回答开始（有 content）就自动收起思考，避免遮挡答案
          assistant.reasoning += data.content || ''
          if (assistant.content) assistant.reasoningOpen = false
          this.statusText = '思考中…'
          this.scrollToBottom()
          break
        case 'rag.sources':
          assistant.sources = data.sources || []
          this.statusText = '已检索到 ' + (data.sources || []).length + ' 条引用'
          break
        case 'agent.plan': {
          // 结构化计划清单：先渲染全部待办（wait），随后 agent.task.done 逐项打勾
          const trace = this.ensureTrace(assistant)
          trace.plan = (data.tasks || []).map(t => ({
            agent: (t && t.agent) || '',
            task: (t && t.task) || '',
            state: 'wait',
            costMs: 0
          }))
          this.statusText = '已拆解 ' + trace.plan.length + ' 个子任务'
          break
        }
        case 'agent.task.done': {
          const trace = this.ensureTrace(assistant)
          const item = trace.plan[data.index]
          const state = data.status === 'done' ? 'done' : 'fail'
          if (item) {
            item.state = state
            item.costMs = data.costMs || 0
          } else if (data.task) {
            // 计划帧丢失（极端情况）的对账兜底：按到达顺序补一项，清单不缺行
            trace.plan.push({ agent: data.agent || '', task: data.task || '', state, costMs: data.costMs || 0 })
          }
          break
        }
        case 'agent.step': {
          const trace = this.ensureTrace(assistant)
          trace.steps.push({ kind: 'step', agent: data.agent || '', type: data.type || '', content: data.content || '' })
          this.statusText = '[' + (data.agent || '') + '] ' + (data.content || '')
          break
        }
        case 'tool.start': {
          const trace = this.ensureTrace(assistant)
          trace.steps.push({
            kind: 'tool',
            name: data.name || '',
            source: data.source || '',
            input: data.input || '',
            output: '',
            state: 'running',
            open: false,
            costMs: 0
          })
          this.statusText = '调用工具：' + (data.name || '')
          break
        }
        case 'tool.end': {
          const trace = this.ensureTrace(assistant)
          // start/end 配对：从后往前找同名且仍在执行中的工具卡片（同名工具可能先后多次调用）
          let t = null
          for (let i = trace.steps.length - 1; i >= 0; i--) {
            const s = trace.steps[i]
            if (s.kind === 'tool' && s.name === (data.name || '') && s.state === 'running') {
              t = s
              break
            }
          }
          if (!t) {
            // start 帧丢失的兜底：直接补一条已完成记录，end 不至于无处安放
            t = { kind: 'tool', name: data.name || '', source: data.source || '', input: '', output: '', state: 'ok', open: false, costMs: 0 }
            trace.steps.push(t)
          }
          t.state = data.success === false ? 'err' : 'ok'
          t.output = data.output || ''
          t.costMs = data.costMs || 0
          break
        }
        case 'artifact':
          assistant.artifacts = assistant.artifacts || []
          assistant.artifacts.push(data)
          break
        case 'msg.end':
          this.streaming = false
          this.statusText = ''
          // 记录本次回答所用模型与总耗时，气泡底部展示小标签
          if (data.model) assistant.model = data.model
          if (data.costMs) assistant.costMs = data.costMs
          this.finalizeTrace(assistant, 'done')
          break
        case 'error':
          assistant.isError = true
          assistant.content = (assistant.content || '') + (data.message || '服务开小差了，请稍后重试')
          this.streaming = false
          this.finalizeTrace(assistant, 'fail')
          break
        default:
          break
      }
      this.scrollToBottom()
    },
    /**
     * 传输层终态兜底（正确性保障）：连接关闭/空闲超时/用户中止这三件事本身
     * 必须终结页面状态——否则 streaming 永远为 true、输入框锁死、“思考中”永久
     * 转圈。msg.end 正常到达时 streaming 已是 false，这里全部幂等跳过。
     */
    onTransportEvent(assistant, type) {
      if (type === '__transport.abort') {
        // 用户点停止：streaming 已在 stop() 置 false，这里只把执行卡片定格
        this.finalizeTrace(assistant, 'stop')
        return
      }
      if (!this.streaming) return
      if (type === '__transport.timeout') {
        // 空闲看门狗触发：服务端卡死或中间层静默吞流，主动断开并给出可重试的错误态
        assistant.isError = true
        assistant.content = (assistant.content || '') +
          (assistant.content ? '\n\n' : '') + '连接空闲超时（90 秒未收到任何数据），已自动断开，请重试'
        this.finalizeTrace(assistant, 'fail')
      } else {
        // __transport.end：服务端没发 msg.end/error 就关闭了连接 → 异常断流，补本地终态
        if (!assistant.content) {
          assistant.isError = true
          assistant.content = '连接已断开（服务端未正常结束），请点击重试'
        }
        this.finalizeTrace(assistant, assistant.isError ? 'fail' : 'done')
      }
      this.streaming = false
      this.statusText = ''
      this.scrollToBottom()
    },
    /** 懒创建执行卡片：第一条 agent/tool 事件到达时才出现 */
    ensureTrace(assistant) {
      if (!assistant.trace) {
        assistant.trace = { status: 'running', expanded: true, plan: [], steps: [] }
      }
      return assistant.trace
    },
    /** 执行卡片终态：完成收起为一行摘要，失败/停止保持展开便于查看现场 */
    finalizeTrace(assistant, status) {
      const trace = assistant.trace
      if (!trace || trace.status !== 'running') return
      trace.status = status
      trace.expanded = status !== 'done'
    },
    /* ===== 模板辅助：执行卡片 ===== */
    traceStateMark(trace) {
      if (trace.status === 'done') return '✓'
      if (trace.status === 'fail') return '✗'
      return '■'
    },
    traceTitle(trace) {
      const finished = trace.plan.filter(p => p.state !== 'wait').length
      const progress = trace.plan.length ? '（' + finished + '/' + trace.plan.length + ' 步）' : ''
      if (trace.status === 'running') return 'Agent 执行中' + progress
      if (trace.status === 'fail') return '执行中断' + progress
      if (trace.status === 'stop') return '已停止' + progress
      return '任务完成' + progress
    },
    planState(trace, pi) {
      const p = trace.plan[pi]
      if (p && p.state === 'done') return 'done'
      if (p && p.state === 'fail') return 'fail'
      return 'wait'
    },
    stepIcon(t) {
      return { think: '💭', act: '⚙️', observe: '👁️', running: '⏳', ok: '✅', err: '❌' }[t] || '·'
    },
    toolMeta(s) {
      if (s.state === 'running') return '执行中…'
      return s.costMs ? s.costMs + 'ms' : ''
    },
    modelTag(msg) {
      const parts = []
      if (msg.model) parts.push('⚡ ' + msg.model)
      if (msg.costMs) parts.push('耗时 ' + (msg.costMs / 1000).toFixed(1) + 's')
      return parts.join(' · ')
    },
    mdBlocks(content) {
      return parseBlocks(content)
    },
    reasoningTitle(msg) {
      if (this.streaming && !msg.content) return '深度思考中…'
      return (msg.reasoningOpen ? '收起思考' : '查看思考') + ' · ' + (msg.reasoning || '').length + ' 字'
    },
    openArtifact(a) {
      // #ifdef H5
      // 产物接口受网关 JWT 保护且有租户校验，直接 window.open 裸链接只会拿到 401 JSON；
      // 带凭证取回二进制后用 blob URL 新窗口预览（同源、可渲染 HTML/图片）
      fetch(config.baseUrl + a.url, { headers: { Authorization: 'Bearer ' + getToken() } })
        .then(res => {
          if (res.status === 401) {
            handleUnauthorized()
            throw new Error('登录已过期')
          }
          if (!res.ok) throw new Error('产物加载失败')
          return res.blob()
        })
        .then(blob => {
          const url = URL.createObjectURL(blob)
          window.open(url, '_blank')
          setTimeout(() => URL.revokeObjectURL(url), 60000)
        })
        .catch(err => uni.showToast({ title: err.message || '产物打开失败', icon: 'none' }))
      // #endif
      // #ifndef H5
      uni.setClipboardData({ data: a.url, success: () => uni.showToast({ title: '链接已复制' }) })
      // #endif
    },
    scrollToBottom() {
      this.$nextTick(() => {
        this.scrollTop = this.scrollTop === 99999 ? 100000 : 99999
      })
    }
  }
}
</script>

<style>
.chat {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: #f3f5fb;
}
.messages {
  flex: 1;
  padding: 24rpx 24rpx 0;
  box-sizing: border-box;
}
.welcome-card {
  background: linear-gradient(135deg, #4f7cff 0%, #6a5cff 100%);
  border-radius: 24rpx;
  padding: 32rpx;
  margin-bottom: 28rpx;
  display: flex;
  flex-direction: column;
  box-shadow: 0 12rpx 32rpx rgba(79, 124, 255, 0.25);
}
.wc-icon {
  font-size: 40rpx;
  margin-bottom: 10rpx;
}
.wc-title {
  color: #ffffff;
  font-size: 30rpx;
  font-weight: bold;
  margin-bottom: 12rpx;
}
.wc-item {
  color: rgba(255, 255, 255, 0.88);
  font-size: 24rpx;
  line-height: 1.7;
}
.msg {
  display: flex;
  align-items: flex-start;
  margin-bottom: 24rpx;
}
.msg.user {
  justify-content: flex-end;
}
.avatar {
  width: 64rpx;
  height: 64rpx;
  border-radius: 20rpx;
  text-align: center;
  line-height: 64rpx;
  font-size: 24rpx;
  font-weight: bold;
  flex-shrink: 0;
}
.ai-avatar {
  background: linear-gradient(135deg, #4f7cff 0%, #6a5cff 100%);
  color: #ffffff;
  margin-right: 16rpx;
}
.user-avatar {
  background: #ffd166;
  color: #7a5b00;
  margin-left: 16rpx;
}
.bubble {
  max-width: 78%;
  border-radius: 22rpx;
  padding: 22rpx 26rpx;
  font-size: 28rpx;
  line-height: 1.65;
  word-break: break-all;
  white-space: pre-wrap;
}
.msg.user .bubble {
  background: linear-gradient(135deg, #4f7cff 0%, #6a5cff 100%);
  color: #ffffff;
  border-top-right-radius: 6rpx;
  box-shadow: 0 6rpx 18rpx rgba(79, 124, 255, 0.28);
}
.msg.assistant .bubble {
  background: #ffffff;
  color: #1f2430;
  border-top-left-radius: 6rpx;
  box-shadow: 0 4rpx 16rpx rgba(31, 36, 48, 0.06);
}
.model-tag {
  display: inline-block;
  margin-top: 14rpx;
  font-size: 20rpx;
  color: #9aa0ae;
  background: #f3f5fb;
  border-radius: 8rpx;
  padding: 4rpx 12rpx;
}
.sources {
  margin-top: 14rpx;
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}
.reasoning {
  margin-bottom: 14rpx;
  background: #f7f8fc;
  border: 1rpx solid #eceef6;
  border-radius: 14rpx;
  overflow: hidden;
}
.reasoning-head {
  display: flex;
  align-items: center;
  gap: 10rpx;
  padding: 12rpx 16rpx;
}
.r-pulse {
  display: flex;
  gap: 5rpx;
  align-items: center;
}
.rp-dot {
  width: 8rpx;
  height: 8rpx;
  border-radius: 50%;
  background: #b9bfd0;
}
.r-pulse.running .rp-dot {
  background: #6a5cff;
  animation: pulse 1.1s infinite ease-in-out;
}
.r-pulse.running .rp-dot:nth-child(2) {
  animation-delay: 0.18s;
}
.r-pulse.running .rp-dot:nth-child(3) {
  animation-delay: 0.36s;
}
@keyframes pulse {
  0%,
  100% {
    opacity: 0.35;
    transform: scale(0.85);
  }
  50% {
    opacity: 1;
    transform: scale(1.1);
  }
}
.r-title {
  font-size: 22rpx;
  color: #6a5cff;
  font-weight: 500;
}
.r-arrow {
  margin-left: auto;
  font-size: 20rpx;
  color: #9aa0ae;
}
.reasoning-body {
  margin: 0 16rpx 14rpx;
  padding: 12rpx 16rpx;
  background: #ffffff;
  border-left: 4rpx solid #c9c2ff;
  border-radius: 10rpx;
}
.reasoning-text {
  display: block;
  font-size: 22rpx;
  line-height: 1.7;
  color: #7a8194;
  white-space: pre-wrap;
  word-break: break-all;
}
.sources-title {
  margin-bottom: 4rpx;
}
.st-text {
  font-size: 22rpx;
  color: #6b7280;
  font-weight: 500;
}
.source-item {
  display: flex;
  align-items: center;
  gap: 10rpx;
  background: #f7f8fc;
  border: 1rpx solid #eceef6;
  border-radius: 10rpx;
  padding: 8rpx 12rpx;
}
.source-idx {
  flex-shrink: 0;
  min-width: 34rpx;
  height: 34rpx;
  line-height: 34rpx;
  text-align: center;
  font-size: 20rpx;
  font-weight: bold;
  color: #ffffff;
  background: linear-gradient(135deg, #4f7cff 0%, #6a5cff 100%);
  border-radius: 8rpx;
}
.source-name {
  flex: 1;
  font-size: 22rpx;
  color: #3d4351;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.source-score {
  flex-shrink: 0;
  font-size: 20rpx;
  color: #4f7cff;
  background: rgba(79, 124, 255, 0.1);
  border-radius: 8rpx;
  padding: 2rpx 10rpx;
}
.artifacts {
  margin-top: 12rpx;
  display: flex;
  flex-wrap: wrap;
  gap: 10rpx;
}
.artifact {
  font-size: 22rpx;
  color: #4f7cff;
  background: rgba(79, 124, 255, 0.08);
  border: 1rpx solid rgba(79, 124, 255, 0.3);
  border-radius: 10rpx;
  padding: 6rpx 14rpx;
}

/* ===== Markdown 渲染 ===== */
.md-p {
  margin: 4rpx 0;
}
.md-p + .md-p {
  margin-top: 12rpx;
}
.md-h {
  margin: 14rpx 0 8rpx;
  font-weight: bold;
  color: #1f2430;
}
.md-list {
  margin: 6rpx 0;
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}
.md-li {
  display: flex;
  align-items: flex-start;
  gap: 8rpx;
}
.md-li-mark {
  flex-shrink: 0;
  color: #6a5cff;
  font-size: 26rpx;
  line-height: 1.65;
  font-weight: 500;
}
.md-li-content {
  flex: 1;
  min-width: 0;
}
.md-codeblock {
  margin: 10rpx 0;
  background: #232733;
  border-radius: 12rpx;
  padding: 16rpx 20rpx;
  overflow-x: auto;
}
.md-code-text {
  font-size: 22rpx;
  line-height: 1.6;
  color: #d6e2ff;
  font-family: Consolas, Menlo, monospace;
  white-space: pre;
}
.md-hr {
  margin: 14rpx 0;
  height: 1rpx;
  background: #e5e8f0;
}
/* rich-text 行内元素（class 在 nodes 中引用） */
.md-b {
  font-weight: bold;
  color: #1f2430;
}
.md-i {
  font-style: italic;
}
.md-inline-code {
  font-family: Consolas, Menlo, monospace;
  font-size: 0.9em;
  color: #b44acc;
  background: #f4f0fa;
  border-radius: 6rpx;
  padding: 2rpx 8rpx;
}

/* ===== 错误气泡 ===== */
.error-bubble {
  background: #fff5f5 !important;
  border: 1rpx solid #ffd4d4;
  color: #c0392b !important;
}

/* ===== Agent 执行过程卡片（计划清单 + 步骤/工具时间线，对齐 ChatGPT/Manus） ===== */
.trace {
  margin-bottom: 14rpx;
  background: #f7f8fc;
  border: 1rpx solid #eceef6;
  border-radius: 14rpx;
  overflow: hidden;
}
.trace.t-running {
  border-color: rgba(106, 92, 255, 0.35);
}
.trace.t-fail {
  border-color: rgba(229, 72, 77, 0.35);
}
.trace-head {
  display: flex;
  align-items: center;
  gap: 10rpx;
  padding: 12rpx 16rpx;
}
.t-spinner {
  flex-shrink: 0;
  width: 22rpx;
  height: 22rpx;
  border-radius: 50%;
  border: 3rpx solid rgba(106, 92, 255, 0.25);
  border-top-color: #6a5cff;
  animation: spin 0.8s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
.t-state {
  flex-shrink: 0;
  font-size: 24rpx;
  font-weight: bold;
  color: #16a34a;
}
.t-fail .t-state {
  color: #e5484d;
}
.t-stop .t-state {
  color: #9aa0ae;
}
.t-title {
  font-size: 22rpx;
  color: #4b5163;
  font-weight: 500;
}
.t-arrow {
  margin-left: auto;
  font-size: 20rpx;
  color: #9aa0ae;
}
.trace-body {
  margin: 0 16rpx 14rpx;
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}
.plan-list {
  background: #ffffff;
  border-radius: 10rpx;
  padding: 10rpx 12rpx;
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}
.plan-item {
  display: flex;
  align-items: flex-start;
  gap: 10rpx;
}
.plan-mark {
  flex-shrink: 0;
  width: 32rpx;
  height: 32rpx;
  line-height: 32rpx;
  text-align: center;
  font-size: 20rpx;
  border-radius: 50%;
  background: #eceef6;
  color: #9aa0ae;
}
.plan-mark.pm-done {
  background: #e8f7ee;
  color: #16a34a;
}
.plan-mark.pm-fail {
  background: #fdeaea;
  color: #e5484d;
}
.plan-text {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2rpx;
}
.plan-agent {
  align-self: flex-start;
  font-size: 18rpx;
  color: #6a5cff;
  background: rgba(106, 92, 255, 0.08);
  border-radius: 6rpx;
  padding: 2rpx 10rpx;
}
.plan-task {
  font-size: 22rpx;
  color: #3d4351;
  line-height: 1.5;
  word-break: break-all;
}
.trace-item {
  display: flex;
  align-items: flex-start;
  gap: 8rpx;
  padding: 2rpx 4rpx;
}
.ti-icon {
  flex-shrink: 0;
  font-size: 22rpx;
  line-height: 1.6;
}
.ti-agent {
  flex-shrink: 0;
  font-size: 18rpx;
  color: #6a5cff;
  background: rgba(106, 92, 255, 0.08);
  border-radius: 6rpx;
  padding: 2rpx 10rpx;
  margin-top: 2rpx;
}
.ti-content {
  font-size: 22rpx;
  color: #5a6172;
  line-height: 1.6;
  word-break: break-all;
  white-space: pre-wrap;
}
/* 工具卡片：一行摘要（可点击展开详情）+ 详情块，纵向排列 */
.tool-cell {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}
.tool-line {
  display: flex;
  align-items: center;
  gap: 8rpx;
}
.tool-name {
  font-size: 22rpx;
  color: #1f2430;
  font-family: Consolas, Menlo, monospace;
  font-weight: 500;
}
.tool-src {
  flex-shrink: 0;
  font-size: 18rpx;
  color: #b06ab0;
  background: rgba(176, 106, 176, 0.1);
  border-radius: 6rpx;
  padding: 2rpx 8rpx;
}
.tool-meta {
  margin-left: auto;
  font-size: 20rpx;
  color: #9aa0ae;
}
.tool-line .t-arrow {
  margin-left: 6rpx;
}
.tool-detail {
  padding: 10rpx 12rpx;
  background: #ffffff;
  border-radius: 10rpx;
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}
.tool-row {
  display: flex;
  align-items: flex-start;
  gap: 8rpx;
}
.tool-k {
  flex-shrink: 0;
  font-size: 20rpx;
  color: #9aa0ae;
}
.tool-v {
  flex: 1;
  min-width: 0;
  font-size: 20rpx;
  color: #3d4351;
  font-family: Consolas, Menlo, monospace;
  white-space: pre-wrap;
  word-break: break-all;
}

/* ===== 失败重试 ===== */
.retry-bar {
  margin-top: 14rpx;
  display: flex;
  justify-content: flex-end;
}
.retry-btn {
  font-size: 22rpx;
  color: #4f7cff;
  border: 1rpx solid rgba(79, 124, 255, 0.4);
  border-radius: 999rpx;
  padding: 8rpx 24rpx;
}

/* ===== 消息入场动画与流式光标 ===== */
.msg {
  animation: fadeUp 0.25s ease both;
}
@keyframes fadeUp {
  from {
    opacity: 0;
    transform: translateY(12rpx);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
.typing-cursor {
  display: inline-block;
  color: #4f7cff;
  animation: blink 0.9s steps(1) infinite;
}
@keyframes blink {
  50% {
    opacity: 0;
  }
}
.thinking {
  display: flex;
  align-items: center;
  margin-bottom: 24rpx;
}
.thinking-bubble {
  display: flex;
  align-items: center;
  gap: 14rpx;
}
.dots {
  display: flex;
  gap: 8rpx;
}
.dot {
  width: 12rpx;
  height: 12rpx;
  border-radius: 50%;
  background: #4f7cff;
  animation: bounce 1.2s infinite ease-in-out;
}
.d2 {
  animation-delay: 0.2s;
}
.d3 {
  animation-delay: 0.4s;
}
@keyframes bounce {
  0%,
  60%,
  100% {
    transform: translateY(0);
    opacity: 0.4;
  }
  30% {
    transform: translateY(-8rpx);
    opacity: 1;
  }
}
.thinking-text {
  font-size: 24rpx;
  color: #9aa0ae;
}
.bottom-space {
  height: 20rpx;
}
.dock {
  background: #ffffff;
  border-radius: 28rpx 28rpx 0 0;
  box-shadow: 0 -8rpx 32rpx rgba(31, 36, 48, 0.08);
  padding-bottom: env(safe-area-inset-bottom);
}
.dock-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18rpx 24rpx 0;
}
.kb-link {
  font-size: 24rpx;
  color: #4f7cff;
}
.model-picker {
  display: flex;
  align-items: center;
  gap: 8rpx;
  padding: 8rpx 20rpx;
  border: 1rpx solid #e8ecf7;
  border-radius: 999rpx;
  background: #f5f7fd;
}
.mp-label {
  font-size: 22rpx;
  color: #1f2430;
  max-width: 280rpx;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.arrow {
  color: #9aa0ae;
  font-size: 20rpx;
}
.clear-model {
  font-size: 20rpx;
  color: #9aa0ae;
  text-decoration: underline;
}
.toolbar {
  display: flex;
  padding: 14rpx 24rpx;
  gap: 14rpx;
}
.scene {
  padding: 10rpx 30rpx;
  border-radius: 999rpx;
  background: #f5f7fd;
  font-size: 24rpx;
  color: #5f5e5a;
  transition: all 0.2s;
}
.scene.active {
  background: linear-gradient(135deg, #4f7cff 0%, #6a5cff 100%);
  color: #ffffff;
  box-shadow: 0 6rpx 16rpx rgba(79, 124, 255, 0.3);
}
.input-bar {
  display: flex;
  align-items: center;
  padding: 8rpx 24rpx 28rpx;
  gap: 14rpx;
}
.input {
  flex: 1;
  height: 84rpx;
  background: #f5f7fd;
  border: 1rpx solid #e8ecf7;
  border-radius: 999rpx;
  padding: 0 30rpx;
  font-size: 28rpx;
  color: #1f2430;
}
.ph {
  color: #b8bfce;
}
.send {
  width: 132rpx;
  height: 84rpx;
  line-height: 84rpx;
  border-radius: 999rpx;
  background: linear-gradient(135deg, #4f7cff 0%, #6a5cff 100%);
  color: #ffffff;
  font-size: 26rpx;
  padding: 0;
  border: none;
  box-shadow: 0 6rpx 18rpx rgba(79, 124, 255, 0.35);
}
.send::after {
  border: none;
}
.send.stop {
  background: #e5484d;
  box-shadow: 0 6rpx 18rpx rgba(229, 72, 77, 0.3);
}
</style>
