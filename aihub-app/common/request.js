import config from './config.js'

/**
 * 登录：网关 /auth/login（免鉴权）
 * 成功后返回 JWT，后续请求以 Authorization: Bearer <token> 携带。
 */
export function login({ tenantCode, username, password }) {
  return new Promise((resolve, reject) => {
    uni.request({
      url: config.baseUrl + '/auth/login',
      method: 'POST',
      header: { 'Content-Type': 'application/json' },
      data: { tenantCode, username, password },
      success: (res) => {
        if (res.statusCode === 200 && res.data && res.data.code === 0) {
          uni.setStorageSync('token', res.data.data.token)
          uni.setStorageSync('userId', res.data.data.userId)
          resolve(res.data.data)
        } else {
          reject(new Error((res.data && res.data.message) || '登录失败'))
        }
      },
      fail: (err) => reject(new Error('网络错误：' + (err.errMsg || '')))
    })
  })
}

export function getToken() {
  return uni.getStorageSync('token') || ''
}

export function logout() {
  uni.removeStorageSync('token')
  uni.reLaunch({ url: '/pages/login/login' })
}
