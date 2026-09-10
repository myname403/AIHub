<script>
export default {
  onLaunch() {
    // 启动时校验登录态，未登录跳转登录页
    const token = uni.getStorageSync('token')
    const pages = getCurrentPages()
    const route = pages.length ? pages[pages.length - 1].route : ''
    if (!token && !route.includes('login')) {
      uni.reLaunch({ url: '/pages/login/login' })
    }
  }
}
</script>

<style>
page {
  background-color: #f5f6f8;
  font-size: 28rpx;
  color: #2c2c2a;
}
</style>
