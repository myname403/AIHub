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
        <view v-if="msg.role === 'assistant'" class="avatar ai-avatar">AI</view>
        <view class="bubble">
          <text>{{ msg.content }}</text>
          <view v-if="msg.sources && msg.sources.length" class="sources">
            <text
              v-for="(s, j) in msg.sources"
              :key="j"
              class="source">[{{ s.index }}] {{ s.docName }}（{{ (s.score * 100).toFixed(0) }}%）</text>
          </view>
          <view v-if="msg.artifacts && msg.artifacts.length" class="artifacts">
            <text
              v-for="(a, j) in msg.artifacts"
              :key="j"
              class="artifact"
              @click="openArtifact(a)">{{ a.name }} ↗</text>
          </view>
          <!-- 回答完成显示所用模型 -->
          <text v-if="msg.model && !streaming" class="model-tag">{{ msg.model }}</text>
        </view>
        <view v-if="msg.role === 'user'" class="avatar user-avatar">我</view>
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
import { streamChat } from '../../common/stream.js'
import { post, get } from '../../common/request.js'

const WELCOME = { role: 'assistant', content: '你好，我是 AIHub 智能助手。可以选择下方模式提问：\n· 对话：普通聊天\n· 知识库：基于知识库回答（带引用）\n· Agent：自动拆解任务并生成图表/表格/网页' }

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
      const assistant = { role: 'assistant', content: '', sources: [], artifacts: [] }
      this.messages.push(assistant)
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
    },
    onEvent(assistant, event) {
      const type = event.e || event.event
      const data = event.data || {}
      switch (type) {
        case 'msg.start':
          if (data.conversationId) this.conversationId = data.conversationId
          break
        case 'token':
          assistant.content += data.content || ''
          this.scrollToBottom()
          break
        case 'rag.sources':
          assistant.sources = data.sources || []
          this.statusText = '已检索到 ' + (data.sources || []).length + ' 条引用'
          break
        case 'agent.step':
          this.statusText = '[' + (data.agent || '') + '.' + (data.type || '') + '] ' + (data.content || '')
          if (data.type === 'act' || data.type === 'observe') {
            this.messages.push({ role: 'system', content: '· ' + (data.content || '') })
          }
          break
        case 'artifact':
          assistant.artifacts = assistant.artifacts || []
          assistant.artifacts.push(data)
          break
        case 'msg.end':
          this.streaming = false
          this.statusText = ''
          // 记录本次回答所用模型，气泡底部展示小标签
          if (data.model) assistant.model = data.model
          break
        case 'error':
          assistant.content += data.message || '出错了'
          this.streaming = false
          break
        default:
          break
      }
      this.scrollToBottom()
    },
    openArtifact(a) {
      // #ifdef H5
      window.open('http://localhost:8080' + a.url, '_blank')
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
.source {
  font-size: 22rpx;
  color: #4f7cff;
  background: rgba(79, 124, 255, 0.08);
  border-radius: 8rpx;
  padding: 6rpx 12rpx;
}
.artifacts {
  margin-top: 12rpx;
  display: flex;
  flex-wrap: wrap;
  gap: 10rpx;
}
.artifact {
  font-size: 22rpx;
  color: #ffffff;
  background: rgba(255, 255, 255, 0.22);
  border: 1rpx solid rgba(255, 255, 255, 0.4);
  border-radius: 10rpx;
  padding: 6rpx 14rpx;
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
