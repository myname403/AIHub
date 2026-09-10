<template>
  <view class="page">
    <view class="section">
      <view class="section-title">
        <text>应用（助手）</text>
        <text class="link" @click="toggleForm">{{ showForm ? '取消' : '新建' }}</text>
      </view>

      <view v-if="showForm" class="form">
        <view class="field">
          <text class="label">名称</text>
          <input v-model="form.name" class="inp" placeholder="如 天机AI助手" />
        </view>
        <view class="field">
          <text class="label">系统提示词</text>
          <textarea v-model="form.systemPrompt" class="area" placeholder="定义助手的角色与边界（可留空）" />
        </view>
        <view class="field">
          <text class="label">Agent 策略</text>
          <picker mode="selector" :range="strategyLabels" :value="strategyIndex" @change="onStrategyChange">
            <view class="picker">{{ strategyLabels[strategyIndex] }}</view>
          </picker>
        </view>
        <view class="field">
          <text class="label">记忆策略</text>
          <picker mode="selector" :range="memoryLabels" :value="memoryIndex" @change="onMemoryChange">
            <view class="picker">{{ memoryLabels[memoryIndex] }}</view>
          </picker>
        </view>
        <view class="field">
          <text class="label">温度（0.00 ~ 2.00，越低越稳定）</text>
          <input v-model="form.temperature" class="inp" type="digit" placeholder="0.70" />
        </view>
        <view class="field row">
          <text class="label">启用</text>
          <switch :checked="form.status === 1" @change="(e) => (form.status = e.detail.value ? 1 : 0)" />
        </view>
        <button class="btn primary" @click="submit">{{ editingId ? '保存修改' : '创建应用' }}</button>
      </view>

      <text v-if="!apps.length" class="empty">暂无应用，点右上角「新建」</text>
      <view v-for="a in apps" :key="a.id" class="item">
        <view class="item-main">
          <view class="item-head">
            <text class="name">{{ a.name }}</text>
            <text v-if="a.status !== 1" class="badge off">已停用</text>
          </view>
          <text class="sub">ID {{ a.id }} · {{ strategyText(a.agentStrategy) }} · {{ memoryText(a.memoryPolicy) }} · 温度 {{ a.temperature }}</text>
          <text class="sub">知识库：{{ (bound[a.id] || []).length ? bound[a.id].join('、') : '未绑定' }}</text>
        </view>
        <view class="item-ops">
          <text class="link" @click="edit(a)">编辑</text>
          <text class="link danger" @click="remove(a)">删除</text>
        </view>
      </view>
    </view>

    <text v-if="tip" class="tip" :class="{ err: isError }">{{ tip }}</text>
  </view>
</template>

<script>
import { get, post, put, del } from '../../common/request.js'

const STRATEGIES = ['none', 'react', 'plan_execute']
const MEMORY = ['window', 'summary']

export default {
  data() {
    return {
      apps: [],
      bound: {},
      showForm: false,
      editingId: null,
      form: this.blankForm(),
      strategyIndex: 0,
      memoryIndex: 0,
      tip: '',
      isError: false,
      strategyLabels: ['无（普通对话）', 'ReAct（工具循环）', 'Plan-Execute（任务拆解）'],
      memoryLabels: ['滑动窗口', '摘要压缩']
    }
  },
  onShow() {
    this.reload()
  },
  methods: {
    blankForm() {
      return {
        name: '',
        systemPrompt: '',
        agentStrategy: STRATEGIES[0],
        memoryPolicy: MEMORY[0],
        temperature: '0.70',
        status: 1
      }
    },
    async reload() {
      try {
        this.apps = (await get('/api/ai/app')) || []
        // 逐个查绑定关系：应用数量很少，且这几个请求彼此独立
        this.apps.forEach(async (a) => {
          try {
            const ids = await get('/api/ai/app/' + a.id + '/knowledge-bases')
            this.$set(this.bound, a.id, ids || [])
          } catch (e) {
            this.$set(this.bound, a.id, [])
          }
        })
      } catch (e) {
        this.fail(e)
      }
    },
    toggleForm() {
      this.showForm = !this.showForm
      if (!this.showForm) {
        this.editingId = null
        this.form = this.blankForm()
      }
    },
    edit(a) {
      this.editingId = a.id
      this.form = {
        name: a.name,
        systemPrompt: a.systemPrompt || '',
        agentStrategy: a.agentStrategy || STRATEGIES[0],
        memoryPolicy: a.memoryPolicy || MEMORY[0],
        temperature: String(a.temperature == null ? 0.7 : a.temperature),
        status: a.status
      }
      const si = STRATEGIES.indexOf(this.form.agentStrategy)
      const mi = MEMORY.indexOf(this.form.memoryPolicy)
      this.strategyIndex = si < 0 ? 0 : si
      this.memoryIndex = mi < 0 ? 0 : mi
      this.showForm = true
    },
    onStrategyChange(e) {
      this.strategyIndex = Number(e.detail.value)
      this.form.agentStrategy = STRATEGIES[this.strategyIndex]
    },
    onMemoryChange(e) {
      this.memoryIndex = Number(e.detail.value)
      this.form.memoryPolicy = MEMORY[this.memoryIndex]
    },
    async submit() {
      this.tip = ''
      if (!this.form.name) {
        this.tip = '名称不能为空'
        this.isError = true
        return
      }
      const temperature = Number(this.form.temperature)
      if (Number.isNaN(temperature) || temperature < 0 || temperature > 2) {
        // 前端先挡一道，避免把明显非法的值发到后端
        this.tip = '温度取值范围 0.00 ~ 2.00'
        this.isError = true
        return
      }
      const payload = {
        name: this.form.name,
        systemPrompt: this.form.systemPrompt || null,
        agentStrategy: this.form.agentStrategy,
        memoryPolicy: this.form.memoryPolicy,
        temperature,
        status: this.form.status
      }
      try {
        if (this.editingId) {
          await put('/api/ai/app/' + this.editingId, payload)
          this.ok('已保存')
        } else {
          await post('/api/ai/app', payload)
          this.ok('已创建')
        }
        this.editingId = null
        this.form = this.blankForm()
        this.showForm = false
        this.reload()
      } catch (e) {
        this.fail(e)
      }
    },
    remove(a) {
      const that = this
      uni.showModal({
        title: '删除应用',
        content: '将同时解除该应用的知识库绑定，确定删除「' + a.name + '」？',
        success(res) {
          if (res.confirm) that.doRemove(a)
        }
      })
    },
    async doRemove(a) {
      try {
        await del('/api/ai/app/' + a.id)
        this.ok('已删除')
        this.reload()
      } catch (e) {
        this.fail(e)
      }
    },
    ok(msg) {
      this.tip = msg
      this.isError = false
    },
    fail(e) {
      this.tip = e.message
      this.isError = true
    },
    strategyText(s) {
      return { none: '普通对话', react: 'ReAct', plan_execute: 'Plan-Execute' }[s] || s
    },
    memoryText(m) {
      return { window: '滑动窗口', summary: '摘要压缩' }[m] || m
    }
  }
}
</script>

<style>
.page {
  padding: 24rpx;
}
.section {
  background: #ffffff;
  border-radius: 12rpx;
  padding: 24rpx;
  margin-bottom: 24rpx;
}
.section-title {
  display: flex;
  justify-content: space-between;
  font-size: 30rpx;
  font-weight: 500;
  margin-bottom: 20rpx;
}
.link {
  color: #185fa5;
  font-size: 26rpx;
}
.link.danger {
  color: #a32d2d;
}
.form {
  background: #f7f8fa;
  border-radius: 10rpx;
  padding: 20rpx;
  margin-bottom: 24rpx;
}
.field {
  margin-bottom: 20rpx;
}
.field.row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.label {
  display: block;
  font-size: 24rpx;
  color: #5f5e5a;
  margin-bottom: 8rpx;
}
.inp,
.picker,
.area {
  background: #ffffff;
  border: 1rpx solid #e0e0e0;
  border-radius: 8rpx;
  padding: 14rpx 20rpx;
  font-size: 26rpx;
  width: 100%;
  box-sizing: border-box;
}
.area {
  height: 160rpx;
}
.btn {
  line-height: 76rpx;
  border-radius: 8rpx;
  font-size: 28rpx;
}
.btn.primary {
  background: #185fa5;
  color: #ffffff;
}
.item {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  padding: 20rpx 0;
  border-bottom: 1rpx solid #f0efec;
}
.item-head {
  display: flex;
  align-items: center;
  gap: 12rpx;
}
.name {
  font-size: 28rpx;
  font-weight: 500;
}
.badge {
  font-size: 20rpx;
  padding: 2rpx 12rpx;
  border-radius: 6rpx;
}
.badge.off {
  background: #f0efec;
  color: #888780;
}
.sub {
  display: block;
  font-size: 22rpx;
  color: #888780;
  margin-top: 6rpx;
}
.item-ops {
  display: flex;
  gap: 20rpx;
}
.empty {
  display: block;
  color: #b4b2a9;
  font-size: 24rpx;
}
.tip {
  display: block;
  margin-top: 8rpx;
  color: #0f6e56;
  font-size: 24rpx;
}
.tip.err {
  color: #a32d2d;
}
</style>
