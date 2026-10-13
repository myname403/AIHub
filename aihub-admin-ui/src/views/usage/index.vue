<script setup lang="ts">
import { ref, reactive, onMounted, onBeforeUnmount, nextTick } from "vue";
// 按需引入的 echarts（@/plugins/echarts 在模块加载时已 use 了 Line/Bar/Pie 与常用组件）
import echarts from "@/plugins/echarts";
import { http } from "@/utils/http";

defineOptions({ name: "Usage" });

const days = ref(7);
const loading = ref(false);

const overview = ref({
  calls: 0,
  tokenIn: 0,
  tokenOut: 0,
  tokens: 0,
  avgCostMs: 0,
  modelCount: 0
});
const byModel = ref<any[]>([]);
const quotas = ref<any[]>([]);
/** 按天趋势：后端已补零（无数据的日期也返回 0），可直接画折线 */
const trend = ref<any[]>([]);
const trendRef = ref<HTMLDivElement>();
/** 图表实例类型用 ReturnType 推导，避免再引一份 echarts 的类型命名空间 */
let chart: ReturnType<typeof echarts.init> | null = null;

const loadData = async () => {
  loading.value = true;
  try {
    overview.value = (await http.request(
      "get",
      "/api/platform/usage/overview",
      {
        params: { days: days.value }
      }
    )) as any;
    byModel.value = (await http.request("get", "/api/platform/usage/by-model", {
      params: { days: days.value }
    })) as any[];
    quotas.value = (await http.request(
      "get",
      "/api/platform/usage/quota"
    )) as any[];
    trend.value = (await http.request("get", "/api/platform/usage/trend", {
      params: { days: days.value }
    })) as any[];
  } finally {
    loading.value = false;
  }
  // DOM 更新后再画（首次进入时容器还没渲染，init 会拿到 0 宽高）
  await nextTick();
  renderTrend();
};

/** 调用次数与 Token 量级差好几个数量级，用双 Y 轴避免小值被压成直线 */
const renderTrend = () => {
  if (!trendRef.value) return;
  if (!chart) chart = echarts.init(trendRef.value);
  chart.setOption({
    tooltip: { trigger: "axis" },
    legend: { data: ["调用次数", "Token 总量"] },
    grid: { left: 56, right: 56, top: 44, bottom: 32 },
    xAxis: {
      type: "category",
      boundaryGap: false,
      data: trend.value.map(i => i.day)
    },
    yAxis: [
      { type: "value", name: "调用次数" },
      { type: "value", name: "Token" }
    ],
    series: [
      {
        name: "调用次数",
        type: "line",
        smooth: true,
        showSymbol: false,
        data: trend.value.map(i => Number(i.calls) || 0)
      },
      {
        name: "Token 总量",
        type: "line",
        smooth: true,
        showSymbol: false,
        yAxisIndex: 1,
        data: trend.value.map(i => Number(i.tokens) || 0)
      }
    ]
  });
  chart.resize();
};

const onResize = () => chart?.resize();

const cards = reactive([
  { key: "calls", label: "调用次数" },
  { key: "tokens", label: "Token 总量" },
  { key: "avgCostMs", label: "平均耗时(ms)" },
  { key: "modelCount", label: "使用模型数" }
]);

onMounted(() => {
  loadData();
  window.addEventListener("resize", onResize);
});

onBeforeUnmount(() => {
  window.removeEventListener("resize", onResize);
  // 手动销毁：纯 SPA 切路由不销毁 DOM 引用时会造成实例泄漏
  chart?.dispose();
  chart = null;
});
</script>

<template>
  <div class="p-4 space-y-4">
    <el-card shadow="never">
      <div class="flex items-center justify-between">
        <span>统计窗口</span>
        <el-radio-group v-model="days" @change="loadData">
          <el-radio-button :value="1">今天</el-radio-button>
          <el-radio-button :value="7">近 7 天</el-radio-button>
          <el-radio-button :value="30">近 30 天</el-radio-button>
          <el-radio-button :value="90">近 90 天</el-radio-button>
        </el-radio-group>
      </div>
    </el-card>

    <el-row :gutter="12">
      <el-col v-for="c in cards" :key="c.key" :span="6">
        <el-card shadow="never">
          <div class="text-gray-500 text-sm">{{ c.label }}</div>
          <div class="text-2xl font-bold mt-2">{{ overview[c.key] ?? 0 }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" header="调用趋势">
      <!-- ECharts 容器：必须有确定高度，否则 init 时高度为 0 画不出来 -->
      <div ref="trendRef" style="width: 100%; height: 280px" />
    </el-card>

    <el-row :gutter="12">
      <el-col :span="12">
        <el-card shadow="never" header="按模型统计">
          <el-table v-loading="loading" :data="byModel" border size="small">
            <el-table-column prop="modelCode" label="模型" min-width="140" />
            <el-table-column prop="calls" label="调用次数" width="100" />
            <el-table-column prop="tokenIn" label="输入 Token" width="110" />
            <el-table-column prop="tokenOut" label="输出 Token" width="110" />
            <el-table-column
              prop="avgCostMs"
              label="平均耗时(ms)"
              width="120"
            />
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="never" header="配额余量">
          <el-table v-loading="loading" :data="quotas" border size="small">
            <el-table-column prop="dimension" label="维度" width="90" />
            <el-table-column prop="period" label="周期" width="80" />
            <el-table-column prop="used" label="已用" width="110" />
            <el-table-column prop="limit" label="限额" width="110" />
            <el-table-column label="使用率">
              <template #default="{ row }">
                <el-progress :percentage="Math.round((row.ratio ?? 0) * 100)" />
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>
