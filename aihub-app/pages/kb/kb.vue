<template>
  <view class="page">
    <view class="section">
      <view class="section-title">
        <text>知识库管理</text>
        <text class="link" @click="reload">刷新</text>
      </view>

      <view v-for="kb in kbs" :key="kb.id" class="kb">
        <view class="kb-head">
          <text class="kb-name">{{ kb.name }}</text>
          <text class="kb-id">ID {{ kb.id }}</text>
        </view>

        <view class="docs">
          <text v-if="!docs[kb.id] || !docs[kb.id].length" class="empty">暂无文档</text>
          <view v-for="d in docs[kb.id] || []" :key="d.id" class="doc">
            <text class="doc-name">{{ d.name }}</text>
            <view class="doc-right">
              <text class="doc-status" :class="'s' + d.status">{{ statusText(d.status) }}</text>
              <text v-if="taskOf(d)" class="doc-progress">
                {{ taskOf(d).progress }}%
              </text>
              <text v-if="d.status === 3" class="link retry" @click="retryIngest(d)">重试</text>
            </view>
          </view>
        </view>

        <view class="ops">
          <button class="mini" @click="pickAndUpload(kb.id)">上传文档</button>
          <button class="mini" @click="bindToApp(kb.id)">绑定到示例应用</button>
        </view>
      </view>

      <view class="create">
        <input v-model="newName" class="input" placeholder="新知识库名称" />
        <button class="mini primary" @click="createKb">创建知识库</button>
      </view>
      <text v-if="tip" class="tip">{{ tip }}</text>
    </view>
  </view>
</template>

<script>
import config from '../../common/config.js'
import { getToken } from '../../common/request.js'

export default {
  data() {
    return { kbs: [], docs: {}, tasks: {}, newName: '', tip: '', timers: {} }
  },
  onShow() {
    this.reload()
  },
  onHide() {
    this.clearTimers()
  },
  onUnload() {
    this.clearTimers()
  },
  methods: {
    header() {
      return { Authorization: 'Bearer ' + getToken(), 'Content-Type': 'application/json' }
    },
    /** 取某文档当前入库任务（用于展示进度） */
    taskOf(doc) {
      return this.tasks[doc.id] || null
    },
    clearTimers() {
      Object.keys(this.timers).forEach((k) => {
        clearInterval(this.timers[k])
        delete this.timers[k]
      })
    },
    reload() {
      uni.request({
        url: config.baseUrl + '/api/ai/kb',
        method: 'GET',
        header: this.header(),
        success: (res) => {
          if (res.data && res.data.code === 0) {
            this.kbs = res.data.data || []
            this.kbs.forEach((kb) => this.loadDocs(kb.id))
          }
        }
      })
    },
    loadDocs(kbId) {
      uni.request({
        url: config.baseUrl + '/api/ai/kb/' + kbId + '/documents',
        method: 'GET',
        header: this.header(),
        success: (res) => {
          if (res.data && res.data.code === 0) {
            this.docs[kbId] = res.data.data || []
          }
        }
      })
    },
    statusText(status) {
      return { 0: '待处理', 1: '处理中', 2: '完成', 3: '失败' }[status] || '未知'
    },
    createKb() {
      if (!this.newName) return
      uni.request({
        url: config.baseUrl + '/api/ai/kb',
        method: 'POST',
        header: this.header(),
        data: { name: this.newName, vectorDim: 1536 },
        success: (res) => {
          if (res.data && res.data.code === 0) {
            this.tip = '创建成功'
            this.newName = ''
            this.reload()
          } else {
            this.tip = (res.data && res.data.message) || '创建失败'
          }
        }
      })
    },
    pickAndUpload(kbId) {
      const that = this
      uni.chooseMessageFile({
        count: 1,
        type: 'file',
        success(res) {
          const file = res.tempFiles[0]
          uni.uploadFile({
            url: config.baseUrl + '/api/ai/kb/' + kbId + '/documents',
            filePath: file.path,
            name: 'file',
            header: { Authorization: 'Bearer ' + getToken() },
            success(up) {
              // 上传接口是异步提交：立即返回 taskId，需轮询进度
              let taskId = null
              try {
                const body = JSON.parse(up.data)
                taskId = body && body.data ? body.data.taskId : null
              } catch (e) {
                taskId = null
              }
              that.tip = taskId ? '已提交，正在处理…' : '上传失败'
              that.loadDocs(kbId)
              if (taskId) {
                that.pollIngest(kbId, taskId)
              }
            },
            fail() {
              that.tip = '上传失败'
            }
          })
        }
      })
    },
    /** 轮询入库进度，终态时停止并刷新文档列表 */
    pollIngest(kbId, taskId) {
      const that = this
      this.stopTimer('task-' + taskId)
      this.timers['task-' + taskId] = setInterval(() => {
        uni.request({
          url: config.baseUrl + '/api/ai/kb/ingest/' + taskId,
          method: 'GET',
          header: that.header(),
          success(res) {
            const task = res.data && res.data.code === 0 ? res.data.data : null
            if (!task) {
              that.stopTimer('task-' + taskId)
              return
            }
            that.tasks[task.docId] = task
            if (task.status === 2) {
              that.tip = '入库完成'
              that.stopTimer('task-' + taskId)
              that.loadDocs(kbId)
            } else if (task.status === 3) {
              that.tip = '入库失败：' + (task.errorMsg || '未知原因')
              that.stopTimer('task-' + taskId)
              that.loadDocs(kbId)
            }
          },
          fail() {
            that.stopTimer('task-' + taskId)
          }
        })
      }, 1500)
    },
    stopTimer(key) {
      if (this.timers[key]) {
        clearInterval(this.timers[key])
        delete this.timers[key]
      }
    },
    /** 失败任务重试 */
    retryIngest(doc) {
      const that = this
      const task = this.taskOf(doc)
      if (!task) {
        this.tip = '未找到入库任务记录'
        return
      }
      uni.request({
        url: config.baseUrl + '/api/ai/kb/ingest/' + task.id + '/retry',
        method: 'POST',
        header: this.header(),
        success(res) {
          const accepted = res.data && res.data.code === 0 && res.data.data && res.data.data.accepted
          that.tip = accepted ? '已重新提交处理' : '无法重试（原始文件已释放，请重新上传）'
          if (accepted) {
            that.pollIngest(doc.kbId, task.id)
          }
        }
      })
    },
    bindToApp(kbId) {
      uni.request({
        url: config.baseUrl + '/api/ai/kb/bind',
        method: 'POST',
        header: this.header(),
        data: { appId: Number(uni.getStorageSync('appId') || 9001), kbId },
        success: () => {
          this.tip = '已绑定到示例应用（对话切到「知识库」模式即可体验 RAG）'
        }
      })
    }
  }
}
</script>

<style>
.page {
  padding: 24rpx;
}
.section-title {
  display: flex;
  justify-content: space-between;
  font-size: 32rpx;
  font-weight: 500;
  margin-bottom: 24rpx;
}
.link {
  color: #185fa5;
  font-size: 26rpx;
}
.kb {
  background: #ffffff;
  border-radius: 12rpx;
  padding: 24rpx;
  margin-bottom: 24rpx;
}
.kb-head {
  display: flex;
  justify-content: space-between;
  margin-bottom: 12rpx;
}
.kb-name {
  font-weight: 500;
}
.kb-id {
  color: #888780;
  font-size: 22rpx;
}
.docs {
  margin-bottom: 16rpx;
}
.doc {
  display: flex;
  justify-content: space-between;
  padding: 8rpx 0;
}
.doc-name {
  font-size: 26rpx;
}
.doc-right {
  display: flex;
  align-items: center;
  gap: 16rpx;
}
.doc-progress {
  font-size: 22rpx;
  color: #185fa5;
}
.link.retry {
  font-size: 22rpx;
}
.doc-status {
  font-size: 22rpx;
  color: #0f6e56;
}
.doc-status.s3 {
  color: #a32d2d;
}
.doc-status.s1 {
  color: #185fa5;
}
.empty {
  color: #b4b2a9;
  font-size: 24rpx;
}
.ops {
  display: flex;
  gap: 16rpx;
}
.mini {
  font-size: 24rpx;
  padding: 0 24rpx;
  line-height: 60rpx;
  background: #e6f1fb;
  color: #185fa5;
  border-radius: 8rpx;
}
.mini.primary {
  background: #185fa5;
  color: #ffffff;
}
.create {
  display: flex;
  gap: 16rpx;
  margin-top: 12rpx;
}
.input {
  flex: 1;
  border: 1rpx solid #e0e0e0;
  border-radius: 8rpx;
  padding: 12rpx 20rpx;
}
.tip {
  display: block;
  margin-top: 20rpx;
  color: #0f6e56;
  font-size: 24rpx;
}
</style>
