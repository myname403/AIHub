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
