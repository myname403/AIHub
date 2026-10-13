<script setup lang="ts">
import { ref, reactive, onMounted } from "vue";
import { message } from "@/utils/message";
import { http } from "@/utils/http";

defineOptions({ name: "AiModel" });

/** 模型行（后端 ModelInfo） */
interface ModelRow {
  id: number;
  providerCode: string;
  modelCode: string;
  baseUrl: string;
  vectorDim: number | null;
  defaultModel: boolean;
  status: number;
  hasApiKey: boolean;
}

/** 路由行（后端 ModelRouteInfo） */
interface RouteRow {
  id: number;
  scene: string;
  primaryModelId: number | null;
  primaryModelCode: string;
  fallbackModelId: number | null;
  fallbackModelCode: string;
}

const loading = ref(false);
const models = ref<ModelRow[]>([]);
const providers = ref<any[]>([]);
const routes = ref<RouteRow[]>([]);

const dialogVisible = ref(false);
const editId = ref<number | null>(null);
const form = reactive({
  providerCode: "deepseek",
  modelCode: "",
  apiKey: "",
  baseUrl: "",
  vectorDim: undefined as number | undefined,
  defaultModel: false,
  status: 1
});

/** 路由编辑 */
const routeDialogVisible = ref(false);
const routeForm = reactive({
  scene: "chat",
  primaryModelId: undefined as number | undefined,
  fallbackModelId: undefined as number | undefined
});

const loadData = async () => {
  loading.value = true;
  try {
    models.value = (await http.request("get", "/api/ai/model")) as any[];
    providers.value = (await http.request(
      "get",
      "/api/ai/model/provider"
    )) as any[];
    routes.value = (await http.request("get", "/api/ai/model/route")) as any[];
  } finally {
    loading.value = false;
  }
};

const openCreate = () => {
  editId.value = null;
  Object.assign(form, {
    providerCode: "deepseek",
    modelCode: "",
    apiKey: "",
    baseUrl: "",
    vectorDim: undefined,
    defaultModel: false,
    status: 1
  });
  dialogVisible.value = true;
};

const openEdit = (row: ModelRow) => {
  editId.value = row.id;
  Object.assign(form, {
    providerCode: row.providerCode,
    modelCode: row.modelCode,
    apiKey: "", // 留空 = 保持原密钥（后端约定）
    baseUrl: row.baseUrl ?? "",
    vectorDim: row.vectorDim ?? undefined,
    defaultModel: row.defaultModel,
    status: row.status
  });
  dialogVisible.value = true;
};

const submit = async () => {
  const payload: any = {
    providerCode: form.providerCode,
    modelCode: form.modelCode,
    baseUrl: form.baseUrl || undefined,
    vectorDim: form.vectorDim,
    defaultModel: form.defaultModel,
    status: form.status
  };
  if (form.apiKey) payload.apiKey = form.apiKey; // 留空不传 = 保持原值
  if (editId.value == null) {
    await http.request("post", "/api/ai/model", { data: payload });
    message("模型已添加", { type: "success" });
  } else {
    await http.request("put", `/api/ai/model/${editId.value}`, {
      data: payload
    });
    message("已保存", { type: "success" });
  }
  dialogVisible.value = false;
  loadData();
};

const doDelete = async (row: ModelRow) => {
  await http.request("delete", `/api/ai/model/${row.id}`);
  message("已删除", { type: "success" });
  loadData();
};

const openRoute = (row?: RouteRow) => {
  routeForm.scene = row?.scene ?? "chat";
  routeForm.primaryModelId = row?.primaryModelId ?? undefined;
  routeForm.fallbackModelId = row?.fallbackModelId ?? undefined;
  routeDialogVisible.value = true;
};

const saveRoute = async () => {
  await http.request("put", "/api/ai/model/route", {
    data: {
      scene: routeForm.scene,
      primaryModelId: routeForm.primaryModelId,
      fallbackModelId: routeForm.fallbackModelId
    }
  });
  message("路由已保存", { type: "success" });
  routeDialogVisible.value = false;
  loadData();
};

onMounted(loadData);
</script>

<template>
  <div class="p-4 space-y-4">
    <!-- 模型列表 -->
    <el-card shadow="never">
      <div class="flex justify-between mb-3">
        <span class="text-sm text-gray-500">
          配置对话用的模型（OpenAI 兼容协议：DeepSeek / 通义 /
          火山方舟等）。密钥 AES 加密落库，编辑时留空表示不修改。
        </span>
        <el-button type="primary" @click="openCreate">添加模型</el-button>
      </div>
      <el-table v-loading="loading" :data="models" border>
        <el-table-column prop="providerCode" label="供应商" width="110" />
        <el-table-column prop="modelCode" label="模型编码" min-width="150" />
        <el-table-column
          prop="baseUrl"
          label="Base URL"
          min-width="220"
          show-overflow-tooltip
        />
        <el-table-column label="密钥" width="90">
          <template #default="{ row }">
            <el-tag :type="row.hasApiKey ? 'success' : 'info'">
              {{ row.hasApiKey ? "已配置" : "未配置" }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="默认" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.defaultModel" type="warning">默认</el-tag>
          </template>
        </el-table-column>
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
            <el-popconfirm title="确认删除该模型？" @confirm="doDelete(row)">
              <template #reference>
                <el-button link type="danger">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 场景路由 -->
    <el-card shadow="never">
      <div class="flex justify-between mb-3">
        <span class="text-sm text-gray-500">
          场景路由：chat=普通对话 / rag=知识库问答 /
          agent=智能体。主模型失败自动降级到备用模型。
        </span>
        <el-button type="primary" @click="openRoute()">新建路由</el-button>
      </div>
      <el-table :data="routes" border>
        <el-table-column prop="scene" label="场景" width="140" />
        <el-table-column
          prop="primaryModelCode"
          label="主模型"
          min-width="180"
        />
        <el-table-column
          prop="fallbackModelCode"
          label="备用模型"
          min-width="180"
        />
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button link type="primary" @click="openRoute(row)"
              >编辑</el-button
            >
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 模型表单 -->
    <el-dialog
      v-model="dialogVisible"
      :title="editId == null ? '添加模型' : '编辑模型'"
      width="520px"
    >
      <el-form label-width="100px">
        <el-form-item label="供应商">
          <el-select v-model="form.providerCode" class="w-full">
            <el-option
              v-for="p in providers"
              :key="p.code"
              :label="p.name"
              :value="p.code"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="模型编码">
          <el-input v-model="form.modelCode" placeholder="如 deepseek-chat" />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input
            v-model="form.apiKey"
            show-password
            :placeholder="editId == null ? 'sk-...' : '留空表示保持原密钥'"
          />
        </el-form-item>
        <el-form-item label="Base URL">
          <el-input v-model="form.baseUrl" placeholder="留空用供应商默认地址" />
        </el-form-item>
        <el-form-item label="向量维度">
          <el-input-number
            v-model="form.vectorDim"
            :min="0"
            placeholder="嵌入模型必填"
          />
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

    <!-- 路由表单 -->
    <el-dialog v-model="routeDialogVisible" title="场景路由" width="480px">
      <el-form label-width="100px">
        <el-form-item label="场景">
          <el-select v-model="routeForm.scene" class="w-full">
            <el-option label="chat 普通对话" value="chat" />
            <el-option label="rag 知识库问答" value="rag" />
            <el-option label="agent 智能体" value="agent-plan" />
          </el-select>
        </el-form-item>
        <el-form-item label="主模型">
          <el-select
            v-model="routeForm.primaryModelId"
            class="w-full"
            clearable
          >
            <el-option
              v-for="m in models"
              :key="m.id"
              :label="m.modelCode"
              :value="m.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="备用模型">
          <el-select
            v-model="routeForm.fallbackModelId"
            class="w-full"
            clearable
          >
            <el-option
              v-for="m in models"
              :key="m.id"
              :label="m.modelCode"
              :value="m.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="routeDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveRoute">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
