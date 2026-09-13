<template>
  <view class="page">
    <view class="hero">
      <view class="hero-glow g1"></view>
      <text class="title">注册新企业</text>
      <text class="sub">注册即创建独立租户 + 管理员账号</text>
    </view>

    <view class="card">
      <text class="sec">企业信息</text>
      <view class="field">
        <text class="field-icon">🏢</text>
        <input v-model="form.companyName" class="input" placeholder="企业名称" placeholder-class="ph" />
      </view>
      <view class="field">
        <text class="field-icon">🔑</text>
        <input v-model="form.tenantCode" class="input" placeholder="租户码（2-32 位字母/数字）" placeholder-class="ph" />
      </view>

      <text class="sec">管理员账号</text>
      <view class="field">
        <text class="field-icon">👤</text>
        <input v-model="form.username" class="input" placeholder="用户名（3-32 位字母/数字/下划线）" placeholder-class="ph" />
      </view>
      <view class="field">
        <text class="field-icon">🔒</text>
        <input v-model="form.password" class="input" password placeholder="密码（6-64 位）" placeholder-class="ph" />
      </view>
      <view class="field">
        <text class="field-icon">✅</text>
        <input v-model="form.password2" class="input" password placeholder="确认密码" placeholder-class="ph" />
      </view>

      <text class="sec">安全验证</text>
      <view class="field captcha-row">
        <text class="field-icon">🛡️</text>
        <input v-model="form.captchaCode" class="input captcha-input" placeholder="验证码" placeholder-class="ph" />
        <image v-if="captchaImg" :src="captchaImg" class="captcha-img" mode="aspectFit" @click="loadCaptcha" />
      </view>

      <button class="btn" :loading="loading" @click="doRegister">注 册</button>
      <text class="link" @click="goLogin">已有账号？返回登录</text>
    </view>
  </view>
</template>

<script>
import config from '../../common/config.js'

export default {
  data() {
    return {
      loading: false,
      captchaImg: '',
      form: {
        companyName: '',
        tenantCode: '',
        username: '',
        password: '',
        password2: '',
        captchaId: '',
        captchaCode: ''
      }
    }
  },
  onLoad() {
    this.loadCaptcha()
  },
  methods: {
    /** 拉取图形验证码（base64 图片 + 一次性 ID） */
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
          this.captchaImg = res.data.data.imageBase64
        }
      } catch (e) {
        uni.showToast({ title: '验证码加载失败', icon: 'none' })
      }
    },
    async doRegister() {
      const f = this.form
      if (!f.companyName || !f.tenantCode || !f.username || !f.password) {
        uni.showToast({ title: '请填写完整信息', icon: 'none' })
        return
      }
      if (f.password !== f.password2) {
        uni.showToast({ title: '两次密码不一致', icon: 'none' })
        return
      }
      this.loading = true
      try {
        const res = await new Promise((resolve, reject) => {
          uni.request({
            url: config.baseUrl + '/auth/register',
            method: 'POST',
            header: { 'Content-Type': 'application/json' },
            data: {
              companyName: f.companyName,
              tenantCode: f.tenantCode,
              username: f.username,
              password: f.password,
              captchaId: f.captchaId,
              captchaCode: f.captchaCode
            },
            success: resolve,
            fail: reject
          })
        })
        if (res.data?.code === 0) {
          uni.showModal({
            title: '注册成功',
            content: `租户码 ${f.tenantCode}，请返回登录`,
            showCancel: false,
            success: () => uni.navigateBack()
          })
        } else {
          uni.showToast({ title: res.data?.message || '注册失败', icon: 'none' })
          this.loadCaptcha() // 失败后换一张验证码
        }
      } catch (e) {
        uni.showToast({ title: '网络错误', icon: 'none' })
      } finally {
        this.loading = false
      }
    },
    goLogin() {
      uni.navigateBack()
    }
  }
}
</script>

<style>
.page {
  min-height: 100vh;
  background: #f3f5fb;
  padding-bottom: 60rpx;
}
.hero {
  height: 260rpx;
  background: linear-gradient(135deg, #4f7cff 0%, #6a5cff 60%, #8b5cf6 100%);
  border-radius: 0 0 50rpx 50rpx;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  position: relative;
  overflow: hidden;
}
.hero-glow {
  position: absolute;
  width: 360rpx;
  height: 360rpx;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.18);
  top: -140rpx;
  right: -100rpx;
}
.title {
  color: #ffffff;
  font-size: 42rpx;
  font-weight: bold;
  position: relative;
}
.sub {
  color: rgba(255, 255, 255, 0.85);
  font-size: 24rpx;
  margin-top: 10rpx;
  position: relative;
}
.card {
  width: 660rpx;
  margin: -70rpx auto 0;
  background: #ffffff;
  border-radius: 28rpx;
  padding: 40rpx 40rpx 44rpx;
  box-shadow: 0 16rpx 48rpx rgba(79, 124, 255, 0.12);
  position: relative;
}
.sec {
  display: block;
  font-size: 26rpx;
  font-weight: bold;
  color: #4f7cff;
  margin: 26rpx 0 16rpx;
}
.field {
  display: flex;
  align-items: center;
  background: #f5f7fd;
  border: 1rpx solid #e8ecf7;
  border-radius: 18rpx;
  padding: 8rpx 24rpx;
  margin-bottom: 22rpx;
}
.field-icon {
  font-size: 30rpx;
  margin-right: 16rpx;
}
.input {
  flex: 1;
  height: 82rpx;
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
  height: 70rpx;
  border-radius: 12rpx;
  background: #ffffff;
}
.btn {
  margin-top: 30rpx;
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
.link {
  display: block;
  text-align: center;
  color: #4f7cff;
  font-size: 26rpx;
  margin-top: 28rpx;
}
</style>
