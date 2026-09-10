<template>
  <view class="chat">
    <scroll-view class="messages" scroll-y :scroll-top="scrollTop" scroll-with-animation>
      <view v-for="(msg, i) in messages" :key="i" class="msg" :class="msg.role">
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
        </view>
      </view>
      <view v-if="streaming" class="hint">
        <text>{{ statusText }}</text>
      </view>
      <view class="bottom-space"></view>
    </scroll-view>

    <view class="nav-row">
      <text class="kb-link" @click="goKb">知识库管理 ›</text>
      <text class="kb-link" @click="goAdmin">管理后台 ›</text>
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
        @confirm="send" />
      <button v-if="!streaming" class="send" @click="send">发送</button>
      <button v-else class="send stop" @click="stop">停止</button>
    </view>
  </view>
</template>

<script>
import { streamChat } from '../../common/stream.js'
import { post } from '../../common/request.js'

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
      scenes: [
        { label: '对话', value: 'chat' },
        { label: '知识库', value: 'rag' },
        { label: 'Agent', value: 'agent' }
      ]
    }
  },
  methods: {
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
          scene: this.scene
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
  display: flex;
  flex-direction: column;
  height: 100vh;
}
.messages {
  flex: 1;
  padding: 24rpx;
  box-sizing: border-box;
}
.msg {
  display: flex;
  margin-bottom: 24rpx;
}
.msg.user {
  justify-content: flex-end;
}
.bubble {
  max-width: 80%;
  padding: 20rpx 24rpx;
  border-radius: 12rpx;
  background: #ffffff;
  white-space: pre-wrap;
  word-break: break-all;
}
.msg.user .bubble {
  background: #185fa5;
  color: #ffffff;
}
.msg.system .bubble {
  background: #f1efe8;
  color: #5f5e5a;
  font-size: 24rpx;
}
.sources {
  margin-top: 16rpx;
  display: flex;
  flex-direction: column;
}
.source {
  font-size: 22rpx;
  color: #185fa5;
  margin-top: 6rpx;
}
.artifacts {
  margin-top: 16rpx;
  display: flex;
  flex-direction: column;
}
.artifact {
  font-size: 24rpx;
  color: #0f6e56;
  margin-top: 8rpx;
}
.hint {
  text-align: center;
  color: #888780;
  font-size: 24rpx;
  padding: 12rpx;
}
.bottom-space {
  height: 24rpx;
}
.nav-row {
  display: flex;
  gap: 32rpx;
  padding: 8rpx 24rpx 0;
}
.kb-link {
  font-size: 24rpx;
  color: #185fa5;
}
.toolbar {
  display: flex;
  padding: 12rpx 24rpx;
  gap: 16rpx;
}
.scene {
  padding: 8rpx 24rpx;
  border-radius: 999rpx;
  background: #ffffff;
  font-size: 24rpx;
  color: #5f5e5a;
}
.scene.active {
  background: #185fa5;
  color: #ffffff;
}
.input-bar {
  display: flex;
  align-items: center;
  padding: 16rpx 24rpx 32rpx;
  background: #ffffff;
}
.input {
  flex: 1;
  border: 1rpx solid #e0e0e0;
  border-radius: 12rpx;
  padding: 16rpx 20rpx;
  margin-right: 16rpx;
}
.send {
  background: #185fa5;
  color: #ffffff;
  font-size: 28rpx;
  padding: 0 32rpx;
  line-height: 72rpx;
}
.send.stop {
  background: #a32d2d;
}
</style>
