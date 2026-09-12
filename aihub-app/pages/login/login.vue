<template>
  <view class="login">
    <view class="card">
      <text class="title">AIHub</text>
      <text class="subtitle">通用 AI 能力中台</text>
      <input v-model="form.tenantCode" class="input" placeholder="租户编码（演示填 demo）" />
      <input v-model="form.username" class="input" placeholder="用户名" />
      <input v-model="form.password" class="input" password placeholder="密码" />
      <button class="btn" :loading="loading" @click="doLogin">登 录</button>
      <text v-if="error" class="error">{{ error }}</text>
    </view>
  </view>
</template>

<script>
import { login } from '../../common/request.js'

export default {
  data() {
    return {
      form: { tenantCode: 'demo', username: 'admin', password: '123456' },
      loading: false,
      error: ''
    }
  },
  methods: {
    async doLogin() {
      if (this.loading) return
      this.loading = true
      this.error = ''
      try {
        await login(this.form)
        uni.reLaunch({ url: '/pages/chat/chat' })
      } catch (e) {
        this.error = e.message
      } finally {
        this.loading = false
      }
    }
  }
}
</script>

<style>
.login {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100vh;
}
.card {
  width: 600rpx;
  padding: 48rpx;
  background: #ffffff;
  border-radius: 16rpx;
  display: flex;
  flex-direction: column;
}
.title {
  font-size: 44rpx;
  font-weight: 500;
  text-align: center;
}
.subtitle {
  font-size: 24rpx;
  color: #888780;
  text-align: center;
  margin-bottom: 40rpx;
}
.input {
  border: 1rpx solid #e0e0e0;
  border-radius: 8rpx;
  padding: 16rpx 20rpx;
  margin-bottom: 24rpx;
}
.btn {
  background: #185fa5;
  color: #ffffff;
  border-radius: 8rpx;
}
.error {
  color: #a32d2d;
  font-size: 24rpx;
  margin-top: 16rpx;
  text-align: center;
}
</style>
