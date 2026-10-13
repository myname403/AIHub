<script setup lang="ts">
import { ref, reactive, onMounted } from "vue";
import { message } from "@/utils/message";
import { http } from "@/utils/http";

defineOptions({ name: "SystemApikey" });

interface KeyRow {
  id: number;
  name: string;
  prefix: string;
  status: number;
  expireAt: string | null;
  lastUsedAt: string | null;
  usedCount: number;
}

const loading = ref(false);
const tableData = ref<KeyRow[]>([]);

const createVisible = ref(false);
const form = reactive({ name: "" });
/** 明文只显示一次 */
const issuedKey = ref("");
const issuedVisible = ref(false);

const loadData = async () => {
  loading.value = true;
  try {
    tableData.value = (await http.request(
      "get",
      "/api/platform/apikey"
    )) as any[];
  } finally {
    loading.value = false;
  }
};

const doCreate = async () => {
  const res: any = await http.request("post", "/api/platform/apikey", {
    data: { name: form.name }
  });
  issuedKey.value = res.apiKey;
  issuedVisible.value = true;
  createVisible.value = false;
  form.name = "";
  loadData();
};

/**
 * 复制刚签发的密钥明文并关闭弹窗。
 *
 * <p>为什么放在方法里而不是模板内联：模板里访问 `navigator` 会被当成组件实例属性
 * （vue-tsc 报 TS2339），而且多行内联箭头函数也不可读。顺带修掉"按钮写着关闭却不关"的问题。
 */
const copyIssuedKey = () => {
  navigator.clipboard?.writeText(issuedKey.value);
  message("已复制", { type: "success" });
  issuedVisible.value = false;
};

const doRevoke = async (row: KeyRow) => {
  await http.request("delete", `/api/platform/apikey/${row.id}`);
  message("已吊销", { type: "success" });
  loadData();
};

onMounted(loadData);
</script>

<template>
  <div class="p-4">
    <el-card shadow="never">
      <div class="flex justify-between mb-3">
        <span class="text-sm text-gray-500">
          开放 API Key 供第三方系统调用 AIHub（请求头
          X-API-Key）。明文只在签发时显示一次。
        </span>
        <el-button type="primary" @click="createVisible = true"
          >签发新 Key</el-button
        >
      </div>

      <el-table v-loading="loading" :data="tableData" border>
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column prop="prefix" label="Key 前缀" min-width="110" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'">
              {{ row.status === 1 ? "启用" : "已吊销" }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="usedCount" label="使用次数" width="100" />
        <el-table-column prop="lastUsedAt" label="最近使用" min-width="160" />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-popconfirm
              v-if="row.status === 1"
              title="吊销后立即失效（网关缓存最长延迟 60 秒），确认？"
              @confirm="doRevoke(row)"
            >
              <template #reference>
                <el-button link type="danger">吊销</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 签发 -->
    <el-dialog v-model="createVisible" title="签发新 API Key" width="420px">
      <el-input
        v-model="form.name"
        placeholder="Key 名称（如：数据分析脚本）"
      />
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="doCreate">签发</el-button>
      </template>
    </el-dialog>

    <!-- 明文展示（仅一次） -->
    <el-dialog v-model="issuedVisible" title="请立即保存密钥" width="560px">
      <el-alert
        type="warning"
        :closable="false"
        class="mb-3"
        title="此密钥不会再次显示，关闭弹窗后无法找回！"
      />
      <el-input :model-value="issuedKey" readonly />
      <template #footer>
        <el-button type="primary" @click="copyIssuedKey">复制并关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>
