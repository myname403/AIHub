<template>
  <view class="page">
    <view class="section">
      <view class="section-title">
        <text>模型实例</text>
        <text class="link" @click="toggleForm">{{ showForm ? '取消' : '新增' }}</text>
      </view>

      <!-- 新增 / 编辑表单 -->
      <view v-if="showForm" class="form">
        <view class="field">
          <text class="label">供应商</text>
          <picker
            mode="selector"
            :range="providerLabels"
            :value="providerIndex"
            @change="onProviderChange">
            <view class="picker">{{ providerLabels[providerIndex] || '请选择' }}</view>
          </picker>
        </view>
        <view class="field">
          <text class="label">模型编码</text>
          <input v-model="form.modelCode" class="inp" placeholder="如 deepseek-chat / gpt-4o-mini" />
        </view>
        <view class="field">
          <text class="label">API Key</text>
          <input
            v-model="form.apiKey"
            class="inp"
            password
            :placeholder="editingId ? '留空表示不修改已有密钥' : '本地模型可留空'" />
        </view>
        <view class="field">
          <text class="label">Base URL</text>
          <input v-model="form.baseUrl" class="inp" placeholder="留空则用供应商默认地址" />
        </view>
        <view class="field">
          <text class="label">向量维度</text>
          <input v-model="form.vectorDim" class="inp" type="number" placeholder="仅嵌入模型需要，如 1536" />
        </view>
        <view class="field row">
          <text class="label">设为默认模型</text>
          <switch :checked="form.defaultModel" @change="(e) => (form.defaultModel = e.detail.value)" />
        </view>
        <view class="field row">
          <text class="label">启用</text>
          <switch :checked="form.status === 1" @change="(e) => (form.status = e.detail.value ? 1 : 0)" />
        </view>
        <button class="btn primary" @click="submit">{{ editingId ? '保存修改' : '创建模型' }}</button>
      </view>

      <text v-if="!models.length" class="empty">还没有配置模型，点右上角「新增」</text>
      <view v-for="m in models" :key="m.id" class="item">
        <view class="item-main">
          <view class="item-head">
            <text class="name">{{ m.modelCode }}</text>
            <text v-if="m.defaultModel" class="badge default">默认</text>
            <text v-if="m.status !== 1" class="badge off">已停用</text>
          </view>
          <text class="sub">
            {{ m.providerCode }}<text v-if="m.baseUrl"> · {{ m.baseUrl }}</text>
          </text>
          <text class="sub">
            密钥：{{ m.hasApiKey ? '已配置' : '未配置' }}<text v-if="m.vectorDim"> · 维度 {{ m.vectorDim }}</text>
          </text>
        </view>
        <view class="item-ops">
          <text class="link" @click="edit(m)">编辑</text>
          <text class="link danger" @click="remove(m)">删除</text>
        </view>
      </view>
    </view>

    <view class="section">
      <view class="section-title"><text>场景路由（决定每个场景用哪个模型）</text></view>
      <view v-for="r in routes" :key="r.scene" class="route">
        <text class="route-scene">{{ sceneText(r.scene) }}</text>
        <text class="route-models">
          主 {{ r.primaryModelCode || '未设' }}<text v-if="r.fallbackModelCode"> / 备 {{ r.fallbackModelCode }}</text>
        </text>
      </view>

      <view class="form">
        <view class="field">
          <text class="label">场景</text>
          <picker mode="selector" :range="sceneLabels" :value="routeIndex" @change="onSceneChange">
            <view class="picker">{{ sceneLabels[routeIndex] }}</view>
          </picker>
        </view>
        <view class="field">
          <text class="label">主模型</text>
          <picker mode="selector" :range="modelLabels" :value="primaryIndex" @change="onPrimaryChange">
            <view class="picker">{{ modelLabels[primaryIndex] || '请选择' }}</view>
          </picker>
        </view>
        <view class="field">
          <text class="label">备用模型</text>
          <picker mode="selector" :range="fallbackOptions" :value="fallbackIndex" @change="onFallbackChange">
            <view class="picker">{{ fallbackOptions[fallbackIndex] }}</view>
          </picker>
        </view>
        <button class="btn primary" @click="saveRoute">保存路由</button>
      </view>
    </view>

    <text v-if="tip" class="tip" :class="{ err: isError }">{{ tip }}</text>
  </view>
</template>

<script>
import { get, post, put, del } from '../../common/request.js'

const SCENES = ['chat', 'rag', 'embed', 'agent-plan']

export default {
  data() {
    return {
      models: [],
      providers: [],
      routes: [],
      showForm: false,
      editingId: null,
      form: this.blankForm(),
      providerIndex: 0,
      routeIndex: 0,
      primaryIndex: 0,
      fallbackIndex: 0,
      tip: '',
      isError: false,
      sceneLabels: ['对话 chat', '知识库 rag', '向量化 embed', 'Agent 规划 agent-plan']
    }
  },
  computed: {
    providerLabels() {
      return this.providers.map((p) => p.name + '（' + p.code + '）')
    },
    modelLabels() {
      return this.models.map((m) => m.modelCode)
    },
    /** 备用模型可以为空，所以比主模型下拉多一个「不设」选项 */
    fallbackOptions() {
      return ['不设'].concat(this.modelLabels)
    }
  },
  onShow() {
    this.reload()
  },
  methods: {
    blankForm() {
      return {
        providerCode: '',
        modelCode: '',
        apiKey: '',
        baseUrl: '',
        vectorDim: '',
        defaultModel: false,
        status: 1
      }
    },
    async reload() {
      try {
        const [providers, models, routes] = await Promise.all([
          get('/api/ai/model/provider'),
          get('/api/ai/model'),
          get('/api/ai/model/route')
        ])
        this.providers = providers || []
        this.models = models || []
        this.routes = routes || []
        if (this.providers.length && !this.form.providerCode) {
          this.form.providerCode = this.providers[0].code
        }
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
    edit(m) {
      this.editingId = m.id
      // apiKey 刻意留空：后端拿不回明文，留空即「保持原值」
      this.form = {
        providerCode: m.providerCode,
        modelCode: m.modelCode,
        apiKey: '',
        baseUrl: m.baseUrl || '',
        vectorDim: m.vectorDim || '',
        defaultModel: !!m.defaultModel,
        status: m.status
      }
      const idx = this.providers.findIndex((p) => p.code === m.providerCode)
      this.providerIndex = idx < 0 ? 0 : idx
      this.showForm = true
    },
    onProviderChange(e) {
      this.providerIndex = Number(e.detail.value)
      this.form.providerCode = this.providers[this.providerIndex].code
    },
    onSceneChange(e) {
      this.routeIndex = Number(e.detail.value)
    },
    onPrimaryChange(e) {
      this.primaryIndex = Number(e.detail.value)
    },
    onFallbackChange(e) {
      this.fallbackIndex = Number(e.detail.value)
    },
    async submit() {
      this.tip = ''
      if (!this.form.providerCode || !this.form.modelCode) {
        this.tip = '供应商与模型编码必填'
        this.isError = true
        return
      }
      const payload = {
        providerCode: this.form.providerCode,
        modelCode: this.form.modelCode,
        apiKey: this.form.apiKey || null,
        baseUrl: this.form.baseUrl || null,
        vectorDim: this.form.vectorDim ? Number(this.form.vectorDim) : null,
        defaultModel: this.form.defaultModel,
        status: this.form.status
      }
      try {
        if (this.editingId) {
          await put('/api/ai/model/' + this.editingId, payload)
          this.ok('已保存')
        } else {
          await post('/api/ai/model', payload)
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
    remove(m) {
      const that = this
      uni.showModal({
        title: '删除模型',
        content: '将同时清除指向 ' + m.modelCode + ' 的场景路由，确定继续？',
        success(res) {
          if (res.confirm) that.doRemove(m)
        }
      })
    },
    async doRemove(m) {
      try {
        await del('/api/ai/model/' + m.id)
        this.ok('已删除')
        this.reload()
      } catch (e) {
        this.fail(e)
      }
    },
    async saveRoute() {
      this.tip = ''
      const primary = this.models[this.primaryIndex]
      if (!primary) {
        this.tip = '请先选择主模型'
        this.isError = true
        return
      }
      const fallback = this.fallbackIndex === 0 ? null : this.models[this.fallbackIndex - 1]
      try {
        await put('/api/ai/model/route', {
          scene: SCENES[this.routeIndex],
          primaryModelId: primary.id,
          fallbackModelId: fallback ? fallback.id : null
        })
        this.ok('路由已保存')
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
    sceneText(s) {
      return { chat: '对话', rag: '知识库', embed: '向量化', 'agent-plan': 'Agent 规划' }[s] || s
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
.picker {
  background: #ffffff;
  border: 1rpx solid #e0e0e0;
  border-radius: 8rpx;
  padding: 14rpx 20rpx;
  font-size: 26rpx;
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
.badge.default {
  background: #e6f1fb;
  color: #185fa5;
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
.route {
  display: flex;
  justify-content: space-between;
  padding: 14rpx 0;
  border-bottom: 1rpx solid #f0efec;
}
.route-scene {
  font-size: 26rpx;
}
.route-models {
  font-size: 24rpx;
  color: #888780;
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
