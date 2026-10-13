<script setup lang="ts">
import { ref, reactive, onMounted } from "vue";
import { message } from "@/utils/message";
import { http } from "@/utils/http";

defineOptions({ name: "AiApp" });

interface AppRow {
  id: number;
  name: string;
  agentStrategy: string | null;
  systemPrompt: string;
  temperature: number | null;
  status: number;
}

const loading = ref(false);
const tableData = ref<AppRow[]>([]);

const dialogVisible = ref(false);
const editId = ref<number | null>(null);
const form = reactive({
  name: "",
  systemPrompt: "",
  agentStrategy: "none",
  temperature: 0.7,
  status: 1
});

const loadData = async () => {
  loading.value = true;
  try {
    tableData.value = (await http.request("get", "/api/ai/app")) as any[];
  } finally {
    loading.value = false;
  }
};

const openCreate = () => {
  editId.value = null;
  Object.assign(form, {
    name: "",
    systemPrompt: "你是 AIHub 智能助手，请用简体中文回答，回答需准确、简洁。",
    agentStrategy: "none",
    temperature: 0.7,
    status: 1
  });
  dialogVisible.value = true;
};

const openEdit = (row: AppRow) => {
  editId.value = row.id;
  Object.assign(form, {
    name: row.name,
    systemPrompt: row.systemPrompt ?? "",
    agentStrategy: row.agentStrategy ?? "none",
    temperature: row.temperature ?? 0.7,
    status: row.status
  });
  dialogVisible.value = true;
};

const submit = async () => {
  const payload = { ...form };
  if (editId.value == null) {
    await http.request("post", "/api/ai/app", { data: payload });
    message("应用已创建", { type: "success" });
  } else {
    await http.request("put", `/api/ai/app/${editId.value}`, { data: payload });
    message("已保存", { type: "success" });
  }
  dialogVisible.value = false;
  loadData();
};

const doDelete = async (row: AppRow) => {
  await http.request("delete", `/api/ai/app/${row.id}`);
  message("已删除", { type: "success" });
  loadData();
};

onMounted(loadData);
</script>

<template>
  <div class="p-4">
    <el-card shadow="never">
      <div class="flex justify-between mb-3">
        <span class="text-sm text-gray-500">
          AI 应用 = 助手配置（系统提示词 + 温度 + Agent 策略）。对话时通过 appId
          指定使用哪个应用。
        </span>
        <el-button type="primary" @click="openCreate">新建应用</el-button>
      </div>
      <el-table v-loading="loading" :data="tableData" border>
        <el-table-column prop="id" label="ID" width="180" />
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column
          prop="systemPrompt"
          label="系统提示词"
          min-width="240"
          show-overflow-tooltip
        />
        <el-table-column prop="agentStrategy" label="Agent 策略" width="110" />
        <el-table-column prop="temperature" label="温度" width="80" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'">
              {{ row.status === 1 ? "启用" : "停用" }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)"
              >编辑</el-button
            >
            <el-popconfirm title="确认删除该应用？" @confirm="doDelete(row)">
              <template #reference>
                <el-button link type="danger">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog
      v-model="dialogVisible"
      :title="editId == null ? '新建应用' : '编辑应用'"
      width="560px"
    >
      <el-form label-width="100px">
        <el-form-item label="名称">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="系统提示词">
          <el-input v-model="form.systemPrompt" type="textarea" :rows="4" />
        </el-form-item>
        <el-form-item label="Agent 策略">
          <el-select v-model="form.agentStrategy" class="w-full">
            <el-option label="none 不启用" value="none" />
            <el-option label="planning 任务规划" value="planning" />
          </el-select>
        </el-form-item>
        <el-form-item label="温度">
          <el-slider v-model="form.temperature" :min="0" :max="2" :step="0.1" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch
            v-model="form.status"
            :active-value="1"
            :inactive-value="0"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>
