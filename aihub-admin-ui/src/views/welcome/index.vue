<script setup lang="ts">
import { ref, onMounted } from "vue";
import { useRouter } from "vue-router";
import { http } from "@/utils/http";

defineOptions({
  name: "Welcome"
});

const router = useRouter();
const loading = ref(false);
const days = ref(7);

const overview = ref({
  calls: 0,
  tokens: 0,
  avgCostMs: 0,
  modelCount: 0
});
const quotas = ref<any[]>([]);

/** 指标卡定义：label + 字段 + 图标 + 渐变色 */
const cards = [
  {
    key: "calls",
    label: "调用次数",
    icon: "📊",
    gradient: "linear-gradient(135deg,#4f7cff,#6a5cff)"
  },
  {
    key: "tokens",
    label: "Token 总量",
    icon: "🔢",
    gradient: "linear-gradient(135deg,#00b4d8,#0096c7)"
  },
  {
    key: "avgCostMs",
    label: "平均耗时 (ms)",
    icon: "⚡",
    gradient: "linear-gradient(135deg,#f59e0b,#f97316)"
  },
  {
    key: "modelCount",
    label: "使用模型数",
    icon: "🤖",
    gradient: "linear-gradient(135deg,#10b981,#059669)"
  }
];

/** 快捷入口 */
const quickLinks = [
  {
    title: "模型管理",
    desc: "配置/切换对话模型",
    path: "/ai/model",
    icon: "🤖"
  },
  {
    title: "用户管理",
    desc: "子用户与角色分配",
    path: "/system/user",
    icon: "👥"
  },
  {
    title: "API Key",
    desc: "签发开放密钥",
    path: "/system/apikey",
    icon: "🔑"
  },
  { title: "用量统计", desc: "调用与配额明细", path: "/usage", icon: "📈" }
];

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
    quotas.value = (await http.request(
      "get",
      "/api/platform/usage/quota"
    )) as any[];
  } finally {
    loading.value = false;
  }
};

const go = (path: string) => router.push(path);

onMounted(loadData);
</script>

<template>
  <div class="p-4 space-y-4">
    <!-- 顶部问候 + 窗口切换 -->
    <el-card shadow="never" class="rounded-xl!">
      <div class="flex items-center justify-between flex-wrap gap-3">
        <div>
          <div class="text-xl font-bold text-gray-800">
            欢迎使用 AIHub 管理控制台
          </div>
          <div class="text-sm text-gray-500 mt-1">
            统一管理模型、应用、用户与用量 —— 数据每 60 秒缓存刷新
          </div>
        </div>
        <el-radio-group v-model="days" @change="loadData">
          <el-radio-button :value="1">今天</el-radio-button>
          <el-radio-button :value="7">近 7 天</el-radio-button>
          <el-radio-button :value="30">近 30 天</el-radio-button>
        </el-radio-group>
      </div>
    </el-card>

    <!-- 指标卡 -->
    <el-row v-loading="loading" :gutter="12">
      <el-col v-for="c in cards" :key="c.key" :xs="12" :sm="6">
        <el-card shadow="hover" class="rounded-xl!">
          <div class="flex items-center gap-4">
            <div
              class="w-12 h-12 rounded-xl flex items-center justify-center text-2xl text-white shrink-0"
              :style="{ background: c.gradient }"
            >
              {{ c.icon }}
            </div>
            <div>
              <div class="text-gray-500 text-sm">{{ c.label }}</div>
              <div class="text-2xl font-bold text-gray-800 mt-1">
                {{ overview[c.key] ?? 0 }}
              </div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 快捷入口 -->
    <el-card shadow="never" class="rounded-xl!" header="快捷入口">
      <el-row :gutter="12">
        <el-col v-for="q in quickLinks" :key="q.path" :xs="12" :sm="6">
          <div class="quick-link" @click="go(q.path)">
            <div class="text-3xl">{{ q.icon }}</div>
            <div class="font-bold text-gray-800 mt-2">{{ q.title }}</div>
            <div class="text-xs text-gray-500 mt-1">{{ q.desc }}</div>
          </div>
        </el-col>
      </el-row>
    </el-card>

    <!-- 配额余量 -->
    <el-card shadow="never" class="rounded-xl!" header="配额余量">
      <el-table v-loading="loading" :data="quotas" border size="small">
        <el-table-column prop="dimension" label="维度" width="110" />
        <el-table-column prop="period" label="周期" width="90" />
        <el-table-column prop="periodKey" label="周期键" width="130" />
        <el-table-column prop="used" label="已用" width="120" />
        <el-table-column prop="limit" label="限额" width="120" />
        <el-table-column label="使用率" min-width="200">
          <template #default="{ row }">
            <el-progress
              :percentage="Math.round((row.ratio ?? 0) * 100)"
              :status="(row.ratio ?? 0) > 0.9 ? 'exception' : 'success'"
            />
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.quick-link {
  padding: 18px;
  text-align: center;
  cursor: pointer;
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 12px;
  transition: all 0.25s;
}

.quick-link:hover {
  border-color: #4f7cff;
  box-shadow: 0 8px 24px rgb(79 124 255 / 15%);
  transform: translateY(-3px);
}
</style>
