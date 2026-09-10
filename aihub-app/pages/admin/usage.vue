<template>
  <view class="page">
    <view class="range">
      <view
        v-for="r in ranges"
        :key="r.value"
        class="range-item"
        :class="{ active: days === r.value }"
        @click="switchDays(r.value)">
        <text>{{ r.label }}</text>
      </view>
    </view>

    <view class="cards">
      <view class="card">
        <text class="card-num">{{ overview.calls }}</text>
        <text class="card-label">调用次数</text>
      </view>
      <view class="card">
        <text class="card-num">{{ formatNum(overview.tokens) }}</text>
        <text class="card-label">Token 消耗</text>
      </view>
      <view class="card">
        <text class="card-num">{{ overview.avgCostMs }}<text class="unit">ms</text></text>
        <text class="card-label">平均耗时</text>
      </view>
      <view class="card">
        <text class="card-num">{{ overview.modelCount }}</text>
        <text class="card-label">活跃模型</text>
      </view>
    </view>

    <view class="section">
      <view class="section-title"><text>Token 趋势</text></view>
      <!-- 90 天窗口下柱子很密，改成横向滚动，每根固定宽度，宁可滑动也不要挤成一团 -->
      <scroll-view class="chart-scroll" scroll-x>
        <view class="chart" :style="{ width: chartWidth }">
          <view v-for="(p, i) in trend" :key="i" class="bar-wrap">
            <view class="bar" :style="{ height: barHeight(p.tokens) + 'rpx' }"></view>
            <text v-if="showLabel(i)" class="bar-label">{{ shortDay(p.day) }}</text>
          </view>
        </view>
      </scroll-view>
      <text v-if="!hasTrendData" class="empty">该时间窗内暂无调用</text>
    </view>

    <view class="section">
      <view class="section-title"><text>配额用量</text></view>
      <text v-if="!quota.length" class="empty">未配置配额策略（默认不限量）</text>
      <view v-for="q in quota" :key="q.dimension + '-' + q.period" class="quota">
        <view class="quota-head">
          <text class="quota-name">{{ dimText(q.dimension) }} · {{ periodText(q.period) }}</text>
          <text class="quota-num" :class="{ warn: q.ratio >= 0.8 }">
            {{ q.used }} / {{ q.limit }}
          </text>
        </view>
        <view class="track">
          <view
            class="fill"
            :class="{ warn: q.ratio >= 0.8 }"
            :style="{ width: (q.ratio * 100).toFixed(1) + '%' }"></view>
        </view>
      </view>
    </view>

    <view class="section">
      <view class="section-title"><text>按模型分布</text></view>
      <text v-if="!byModel.length" class="empty">暂无数据</text>
      <view v-for="m in byModel" :key="m.modelCode" class="model-row">
        <view class="model-left">
          <text class="model-code">{{ m.modelCode }}</text>
          <text class="model-sub">{{ m.calls }} 次 · {{ m.avgCostMs }}ms</text>
        </view>
        <text class="model-tokens">{{ formatNum(m.tokenIn + m.tokenOut) }} tk</text>
      </view>
    </view>

    <text v-if="tip" class="tip" :class="{ err: isError }">{{ tip }}</text>
  </view>
</template>

<script>
import { get } from '../../common/request.js'

export default {
  data() {
    return {
      days: 7,
      ranges: [
        { label: '近 7 天', value: 7 },
        { label: '近 30 天', value: 30 },
        { label: '近 90 天', value: 90 }
      ],
      overview: { calls: 0, tokens: 0, avgCostMs: 0, modelCount: 0 },
      trend: [],
      byModel: [],
      quota: [],
      tip: '',
      isError: false
    }
  },
  computed: {
    hasTrendData() {
      return this.trend.some((p) => p.tokens > 0 || p.calls > 0)
    },
    /** 柱状图不需要从 0 起算的严格比例，取窗口内峰值做归一化即可 */
    peak() {
      return this.trend.reduce((max, p) => Math.max(max, p.tokens), 0) || 1
    },
    /** 每根柱子 44rpx；不足时撑满容器宽度，超出时横向滚动 */
    chartWidth() {
      return Math.max(this.trend.length * 44, 660) + 'rpx'
    }
  },
  onShow() {
    this.reload()
  },
  methods: {
    switchDays(value) {
      if (this.days === value) return
      this.days = value
      this.reload()
    },
    async reload() {
      this.tip = ''
      this.isError = false
      try {
        // 四个接口互相独立：用 allSettled，单个失败不影响其余板块展示
        const [overview, trend, byModel, quota] = await Promise.allSettled([
          get('/api/platform/usage/overview?days=' + this.days),
          get('/api/platform/usage/trend?days=' + this.days),
          get('/api/platform/usage/by-model?days=' + this.days),
          get('/api/platform/usage/quota')
        ])
        if (overview.status === 'fulfilled') this.overview = overview.value || this.overview
        if (trend.status === 'fulfilled') this.trend = trend.value || []
        if (byModel.status === 'fulfilled') this.byModel = byModel.value || []
        if (quota.status === 'fulfilled') this.quota = quota.value || []

        const failed = [overview, trend, byModel, quota].filter((r) => r.status === 'rejected')
        if (failed.length) {
          this.tip = '部分数据加载失败：' + failed[0].reason.message
          this.isError = true
        }
      } catch (e) {
        this.tip = e.message
        this.isError = true
      }
    },
    barHeight(tokens) {
      // 最小 4rpx，让「有调用但 token 很少」的日期也能看见柱子
      return tokens <= 0 ? 4 : Math.max(4, Math.round((tokens / this.peak) * 200))
    },
    /**
     * 窗口越长柱子越密，标签没必要每根都画，否则相邻文字会叠在一起。
     * 14 天以内每根都标，超过则每 7 天标一次。
     */
    showLabel(i) {
      return this.days <= 14 || i % 7 === 0
    },
    shortDay(day) {
      // 2026-09-10 → 09-10，比完整日期短一半
      return day ? day.slice(5) : ''
    },
    formatNum(n) {
      if (!n) return '0'
      if (n >= 1000000) return (n / 1000000).toFixed(1) + 'M'
      if (n >= 1000) return (n / 1000).toFixed(1) + 'k'
      return String(n)
    },
    dimText(d) {
      return { request: '调用次数', token: 'Token', doc: '文档入库', task: 'Agent 任务' }[d] || d
    },
    periodText(p) {
      return { day: '每日', month: '每月' }[p] || p
    }
  }
}
</script>

<style>
.page {
  padding: 24rpx;
}
.range {
  display: flex;
  gap: 16rpx;
  margin-bottom: 24rpx;
}
.range-item {
  flex: 1;
  text-align: center;
  font-size: 26rpx;
  line-height: 64rpx;
  border-radius: 8rpx;
  background: #ffffff;
  color: #5f5e5a;
}
.range-item.active {
  background: #185fa5;
  color: #ffffff;
}
.cards {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
  margin-bottom: 24rpx;
}
.card {
  width: calc(50% - 8rpx);
  box-sizing: border-box;
  background: #ffffff;
  border-radius: 12rpx;
  padding: 24rpx;
}
.card-num {
  font-size: 40rpx;
  font-weight: 500;
  color: #1a1a18;
}
.unit {
  font-size: 22rpx;
  color: #888780;
  margin-left: 4rpx;
}
.card-label {
  display: block;
  font-size: 24rpx;
  color: #888780;
  margin-top: 8rpx;
}
.section {
  background: #ffffff;
  border-radius: 12rpx;
  padding: 24rpx;
  margin-bottom: 24rpx;
}
.section-title {
  font-size: 30rpx;
  font-weight: 500;
  margin-bottom: 20rpx;
}
.chart-scroll {
  width: 100%;
}
.chart {
  display: flex;
  align-items: flex-end;
  height: 240rpx;
  gap: 6rpx;
}
.bar-wrap {
  width: 38rpx;
  flex: none;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-end;
  height: 100%;
}
.bar {
  width: 100%;
  background: #185fa5;
  border-radius: 4rpx 4rpx 0 0;
  min-height: 4rpx;
}
.bar-label {
  font-size: 18rpx;
  color: #b4b2a9;
  margin-top: 8rpx;
}
.quota {
  margin-bottom: 24rpx;
}
.quota-head {
  display: flex;
  justify-content: space-between;
  margin-bottom: 10rpx;
}
.quota-name {
  font-size: 26rpx;
}
.quota-num {
  font-size: 24rpx;
  color: #888780;
}
.quota-num.warn {
  color: #a32d2d;
}
.track {
  height: 16rpx;
  background: #f0efec;
  border-radius: 8rpx;
  overflow: hidden;
}
.fill {
  height: 100%;
  background: #0f6e56;
  border-radius: 8rpx;
}
.fill.warn {
  background: #a32d2d;
}
.model-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16rpx 0;
  border-bottom: 1rpx solid #f0efec;
}
.model-code {
  font-size: 26rpx;
}
.model-sub {
  display: block;
  font-size: 22rpx;
  color: #888780;
  margin-top: 4rpx;
}
.model-tokens {
  font-size: 26rpx;
  color: #185fa5;
}
.empty {
  display: block;
  color: #b4b2a9;
  font-size: 24rpx;
}
.tip {
  display: block;
  margin-top: 8rpx;
  color: #0f6e56;
  font-size: 24rpx;
}
.tip.err {
  color: #a32d2d;
}
</style>
