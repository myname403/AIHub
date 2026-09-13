<template>
  <view class="login">
    <!-- 顶部渐变背景区 -->
    <view class="hero">
      <view class="hero-glow g1"></view>
      <view class="hero-glow g2"></view>
      <text class="logo">AI</text>
      <text class="title">AIHub</text>
      <text class="subtitle">通用 AI 能力中台</text>
    </view>

    <!-- 登录卡片（上移叠在渐变上） -->
    <view class="card">
      <text class="card-title">欢迎回来</text>
      <text class="card-sub">登录你的工作区</text>

      <view class="field">
        <text class="field-icon">🏢</text>
        <input v-model="form.tenantCode" class="input" placeholder="租户编码" placeholder-class="ph" />
      </view>
      <view class="field">
        <text class="field-icon">👤</text>
        <input v-model="form.username" class="input" placeholder="用户名" placeholder-class="ph" />
      </view>
      <view class="field">
        <text class="field-icon">🔒</text>
        <input v-model="form.password" class="input" password placeholder="密码" placeholder-class="ph" />
      </view>
      <view class="field captcha-row">
        <text class="field-icon">🛡️</text>
        <input v-model="form.captchaCode" class="input captcha-input" placeholder="验证码" placeholder-class="ph" />
        <image
          v-if="captchaImg"
          :src="captchaImg"
          class="captcha-img"
          mode="aspectFit"
          @click="loadCaptcha"
        />
      </view>

      <button class="btn" :loading="loading" @click="doLogin">登 录</button>
      <text class="register-link" @click="goRegister">没有账号？注册新企业 →</text>
      <text v-if="error" class="error">{{ error }}</text>
    </view>

    <text class="foot">AIHub · 让 AI 触手可及</text>
  </view>
</template>

<script>
import { login } from '../../common/request.js'
import config from '../../common/config.js'

export default {
  data() {
    return {
      form: { tenantCode: 'demo', username: 'admin', password: '123456', captchaId: '', captchaCode: '' },
      captchaImg: '',
      loading: false,
      error: ''
    }
  },
  onLoad() {
    this.loadCaptcha()
  },
  methods: {
    /** 拉取图形验证码（与注册页同一接口；点击图片可刷新） */
    async loadCaptcha() {
      try {
        const res = await new Promise((resolve, reject) => {
          uni.request({
            url: config.baseUrl + '/auth/captcha',
            method: 'GET',
            success: resolve,
            fail: reject
          })
        })
        if (res.data?.code === 0) {
          this.form.captchaId = res.data.data.id
          this.form.captchaCode = ''
          this.captchaImg = res.data.data.imageBase64
        }
      } catch (e) {
        uni.showToast({ title: '验证码加载失败', icon: 'none' })
      }
    },
    async doLogin() {
      if (this.loading) return
      this.loading = true
      this.error = ''
      try {
        await login(this.form)
        uni.reLaunch({ url: '/pages/chat/chat' })
      } catch (e) {
        this.error = e.message
        this.loadCaptcha() // 失败后换一张验证码
      } finally {
        this.loading = false
      }
    },
    goRegister() {
      uni.navigateTo({ url: '/pages/register/register' })
    }
  }
}
</script>

<style>
.login {
  min-height: 100vh;
  background: #f3f5fb;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.hero {
  width: 100%;
  height: 420rpx;
  background: linear-gradient(135deg, #4f7cff 0%, #6a5cff 60%, #8b5cf6 100%);
  border-radius: 0 0 60rpx 60rpx;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  position: relative;
  overflow: hidden;
}
.hero-glow {
  position: absolute;
  border-radius: 50%;
  opacity: 0.25;
}
.g1 {
  width: 400rpx;
  height: 400rpx;
  background: #ffffff;
  top: -160rpx;
  left: -120rpx;
}
.g2 {
  width: 300rpx;
  height: 300rpx;
  background: #a5b8ff;
  bottom: -120rpx;
  right: -80rpx;
}
.logo {
  width: 108rpx;
  height: 108rpx;
  line-height: 108rpx;
  text-align: center;
  border-radius: 32rpx;
  background: rgba(255, 255, 255, 0.22);
  color: #ffffff;
  font-size: 44rpx;
  font-weight: bold;
  margin-bottom: 16rpx;
  position: relative;
}
.title {
  color: #ffffff;
  font-size: 48rpx;
  font-weight: bold;
  letter-spacing: 4rpx;
  position: relative;
}
.subtitle {
  color: rgba(255, 255, 255, 0.85);
  font-size: 24rpx;
  margin-top: 8rpx;
  position: relative;
}
.card {
  width: 640rpx;
  margin-top: -90rpx;
  background: #ffffff;
  border-radius: 28rpx;
  padding: 48rpx 44rpx 40rpx;
  box-shadow: 0 16rpx 48rpx rgba(79, 124, 255, 0.12);
  display: flex;
  flex-direction: column;
  position: relative;
}
.card-title {
  font-size: 40rpx;
  font-weight: bold;
  color: #1f2430;
}
.card-sub {
  font-size: 24rpx;
  color: #9aa0ae;
  margin: 8rpx 0 36rpx;
}
.field {
  display: flex;
  align-items: center;
  background: #f5f7fd;
  border: 1rpx solid #e8ecf7;
  border-radius: 18rpx;
  padding: 8rpx 24rpx;
  margin-bottom: 26rpx;
  transition: all 0.2s;
}
.field:focus-within {
  border-color: #4f7cff;
  background: #ffffff;
  box-shadow: 0 0 0 6rpx rgba(79, 124, 255, 0.08);
}
.field-icon {
  font-size: 30rpx;
  margin-right: 16rpx;
}
.input {
  flex: 1;
  height: 84rpx;
  font-size: 28rpx;
  color: #1f2430;
  background: transparent;
}
.ph {
  color: #b8bfce;
}
.captcha-row .captcha-input {
  flex: 1;
}
.captcha-img {
  width: 210rpx;
  height: 72rpx;
  border-radius: 12rpx;
  background: #ffffff;
}
.btn {
  margin-top: 16rpx;
  height: 92rpx;
  line-height: 92rpx;
  border-radius: 18rpx;
  background: linear-gradient(135deg, #4f7cff 0%, #6a5cff 100%);
  color: #ffffff;
  font-size: 32rpx;
  font-weight: bold;
  letter-spacing: 8rpx;
  box-shadow: 0 10rpx 28rpx rgba(79, 124, 255, 0.35);
  border: none;
}
.btn::after {
  border: none;
}
.register-link {
  color: #4f7cff;
  font-size: 26rpx;
  margin-top: 30rpx;
  text-align: center;
}
.error {
  color: #e5484d;
  font-size: 24rpx;
  margin-top: 20rpx;
  text-align: center;
  background: #fdeced;
  border-radius: 12rpx;
  padding: 12rpx;
}
.foot {
  color: #c3c9d6;
  font-size: 22rpx;
  margin-top: auto;
  padding: 40rpx 0;
}
</style>
