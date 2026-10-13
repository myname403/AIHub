<script setup lang="ts">
import { ref, reactive, onMounted } from "vue";
import { message } from "@/utils/message";
import { http } from "@/utils/http";

defineOptions({ name: "AiKb" });

interface KbRow {
  id: number;
  name: string;
  enabled: boolean;
  docCount?: number;
}

const loading = ref(false);
const tableData = ref<KbRow[]>([]);
const createVisible = ref(false);
const form = reactive({ name: "" });

const loadData = async () => {
  loading.value = true;
  try {
    tableData.value = (await http.request("get", "/api/ai/kb")) as any[];
  } finally {
    loading.value = false;
  }
};

const doCreate = async () => {
  await http.request("post", "/api/ai/kb", { data: { name: form.name } });
  message("知识库已创建，可在对话端上传文档入库", { type: "success" });
  createVisible.value = false;
  form.name = "";
  loadData();
};

onMounted(loadData);
</script>

<template>
  <div class="p-4">
    <el-card shadow="never">
      <div class="flex justify-between mb-3">
        <span class="text-sm text-gray-500">
          RAG 知识库：创建后绑定到应用（/api/ai/kb/bind），用户在对话端上传文档
          → 切片向量化 → 检索问答。
        </span>
        <el-button type="primary" @click="createVisible = true"
          >新建知识库</el-button
        >
      </div>
      <el-table v-loading="loading" :data="tableData" border>
        <el-table-column prop="id" label="ID" width="200" />
        <el-table-column prop="name" label="名称" min-width="200" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'">
              {{ row.enabled ? "启用" : "停用" }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="createVisible" title="新建知识库" width="420px">
      <el-input v-model="form.name" placeholder="知识库名称（如：产品手册）" />
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="doCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>
