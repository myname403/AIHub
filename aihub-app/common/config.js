/**
 * 全局配置。
 * H5 本地开发走网关 8080；小程序真机需改为局域网 IP 或线上域名（并在微信后台配置合法域名）。
 */
const config = {
  // #ifdef H5
  baseUrl: 'http://localhost:8080',
  // #endif
  // #ifndef H5
  baseUrl: 'http://192.168.1.100:8080',
  // #endif
  scene: 'chat' // chat / rag / agent
}

export default config
