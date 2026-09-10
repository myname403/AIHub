<template>
  <view class="page">
    <view class="hero">
      <text class="hero-title">管理后台</text>
      <text class="hero-sub">模型配置、应用编排与用量计量</text>
    </view>

    <view v-for="e in entries" :key="e.path" class="entry" @click="go(e.path)">
      <view class="entry-main">
        <text class="entry-title">{{ e.title }}</text>
        <text class="entry-desc">{{ e.desc }}</text>
      </view>
      <text class="arrow">›</text>
    </view>
  </view>
</template>

<script>
export default {
  data() {
    return {
      entries: [
        {
          title: '用量看板',
          desc: '调用次数 / Token 消耗 / 趋势与配额余量',
          path: '/pages/admin/usage'
        },
        {
          title: '模型管理',
          desc: '配置供应商密钥、默认模型与场景路由',
          path: '/pages/admin/model'
        },
        {
          title: '应用配置',
          desc: '创建助手、设置提示词与 Agent / 记忆策略',
          path: '/pages/admin/app'
        }
      ]
    }
  },
  methods: {
    go(path) {
      uni.navigateTo({ url: path })
    }
  }
}
</script>

<style>
.page {
  padding: 24rpx;
}
.hero {
  padding: 32rpx 8rpx 40rpx;
}
.hero-title {
  display: block;
  font-size: 44rpx;
  font-weight: 500;
}
.hero-sub {
  display: block;
  margin-top: 12rpx;
  font-size: 26rpx;
  color: #888780;
}
.entry {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #ffffff;
  border-radius: 12rpx;
  padding: 32rpx 28rpx;
  margin-bottom: 20rpx;
}
.entry-title {
  display: block;
  font-size: 30rpx;
  font-weight: 500;
}
.entry-desc {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
  color: #888780;
}
.arrow {
  font-size: 40rpx;
  color: #b4b2a9;
}
</style>
