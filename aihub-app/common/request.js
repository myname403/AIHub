import config from './config.js'

/**
 * 登录：网关 /auth/login（免鉴权）
 * 成功后返回 JWT，后续请求以 Authorization: Bearer <token> 携带。
 */
export function login({ tenantCode, username, password, captchaId, captchaCode }) {
  return new Promise((resolve, reject) => {
    uni.request({
      url: config.baseUrl + '/auth/login',
      method: 'POST',
      header: { 'Content-Type': 'application/json' },
      data: { tenantCode, username, password, captchaId, captchaCode },
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

/**
 * 会话失效统一处理：网关对 token 过期/缺失返回 HTTP 401 + code:10002。
 * 这里清除本地登录态并强制回到登录页，供 request() 与流式对话复用。
 * 用 redirecting 标志位去重：并发请求同时收到 401 时只跳转一次。
 */
let redirecting = false
export function handleUnauthorized() {
  uni.removeStorageSync('token')
  uni.removeStorageSync('userId')
  if (redirecting) return
  redirecting = true
  uni.showToast({ title: '登录已过期，请重新登录', icon: 'none' })
  uni.reLaunch({
    url: '/pages/login/login',
    complete: () => setTimeout(() => { redirecting = false }, 800)
  })
}

/** 判断响应是否为「未授权/登录过期」：HTTP 401 或业务码 10002 */
function isUnauthorized(res) {
  return res.statusCode === 401 || (res.data && res.data.code === 10002)
}

/**
 * 通用请求（带 JWT）。返回响应体中的 data 部分。
 *
 * 判断成功的条件有两个，缺一不可：
 *   1. HTTP 200；2. 业务码 code === 0。
 * 只判 HTTP 状态会把「网关放行但业务报错」当成成功，前端拿到的数据是 undefined。
 */
export function request(method, path, data) {
  return new Promise((resolve, reject) => {
    uni.request({
      url: config.baseUrl + path,
      method,
      header: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer ' + getToken()
      },
      data,
      success: (res) => {
        if (res.statusCode === 200 && res.data && res.data.code === 0) {
          resolve(res.data.data)
        } else if (isUnauthorized(res)) {
          // token 过期：跳转登录页，同时 reject 让调用方停止后续处理
          handleUnauthorized()
          reject(new Error('登录已过期'))
        } else {
          reject(new Error((res.data && res.data.message) || '请求失败'))
        }
      },
      fail: (err) => reject(new Error('网络错误：' + (err.errMsg || '')))
    })
  })
}

/**
 * 通用 POST（带 JWT）。返回响应体中的 data 部分。
 */
export function post(path, data) {
  return request('POST', path, data)
}

export function get(path) {
  return request('GET', path)
}

export function put(path, data) {
  return request('PUT', path, data)
}

export function del(path, data) {
  return request('DELETE', path, data)
}

export function logout() {
  uni.removeStorageSync('token')
  uni.reLaunch({ url: '/pages/login/login' })
}
