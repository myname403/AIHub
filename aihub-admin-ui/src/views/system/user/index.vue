<script setup lang="ts">
import { ref, reactive, onMounted } from "vue";
import { message } from "@/utils/message";
import { http } from "@/utils/http";

defineOptions({ name: "SystemUser" });

/** 用户行（后端 SysUser，不含密码哈希） */
interface UserRow {
  id: number;
  username: string;
  nickname: string;
  status: number;
  createTime: string;
}

/** 角色选项（下拉框） */
interface RoleOption {
  id: number;
  code: string;
  name: string;
}

const loading = ref(false);
const tableData = ref<UserRow[]>([]);
const total = ref(0);
const query = reactive({ pageNo: 1, pageSize: 10, keyword: "" });
const roleOptions = ref<RoleOption[]>([]);

/** 新增/编辑弹窗（共用：editId 为空 = 新增） */
const dialogVisible = ref(false);
const editId = ref<number | null>(null);
const form = reactive({
  username: "",
  nickname: "",
  password: "",
  roleIds: [] as number[]
});

/** 重置密码弹窗 */
const resetVisible = ref(false);
const resetTarget = ref<UserRow | null>(null);
const resetForm = reactive({ newPassword: "" });

const loadData = async () => {
  loading.value = true;
  try {
    const page: any = await http.request(
      "get",
      "/api/platform/system/user/page",
      {
        params: query
      }
    );
    tableData.value = page.records ?? [];
    total.value = Number(page.total ?? 0);
  } finally {
    loading.value = false;
  }
};

const loadRoles = async () => {
  roleOptions.value = (await http.request(
    "get",
    "/api/platform/system/role/list"
  )) as any[];
};

const openCreate = () => {
  editId.value = null;
  Object.assign(form, {
    username: "",
    nickname: "",
    password: "",
    roleIds: []
  });
  dialogVisible.value = true;
};

const openEdit = async (row: UserRow) => {
  editId.value = row.id;
  Object.assign(form, {
    username: row.username,
    nickname: row.nickname,
    password: "",
    roleIds: await http.request(
      "get",
      `/api/platform/system/user/${row.id}/role-ids`
    )
  });
  dialogVisible.value = true;
};

const submit = async () => {
  if (editId.value == null) {
    await http.request("post", "/api/platform/system/user", {
      data: {
        username: form.username,
        nickname: form.nickname,
        password: form.password,
        roleIds: form.roleIds
      }
    });
    message("新增成功", { type: "success" });
  } else {
    await http.request("put", `/api/platform/system/user/${editId.value}`, {
      data: { nickname: form.nickname }
    });
    if (form.roleIds.length > 0) {
      await http.request(
        "put",
        `/api/platform/system/user/${editId.value}/assign-roles`,
        {
          data: { roleIds: form.roleIds }
        }
      );
    }
    message("修改成功", { type: "success" });
  }
  dialogVisible.value = false;
  loadData();
};

const toggleStatus = async (row: UserRow) => {
  await http.request("put", `/api/platform/system/user/${row.id}`, {
    data: { status: row.status === 1 ? 0 : 1 }
  });
  message(row.status === 1 ? "已停用" : "已启用", { type: "success" });
  loadData();
};

const doResetPassword = async () => {
  await http.request(
    "put",
    `/api/platform/system/user/${resetTarget.value?.id}/reset-password`,
    {
      data: { newPassword: resetForm.newPassword }
    }
  );
  message("密码已重置", { type: "success" });
  resetVisible.value = false;
};

const doDelete = async (row: UserRow) => {
  await http.request("delete", `/api/platform/system/user/${row.id}`);
  message("已删除", { type: "success" });
  loadData();
};

onMounted(() => {
  loadData();
  loadRoles();
});
</script>

<template>
  <div class="p-4">
    <el-card shadow="never">
      <div class="flex justify-between mb-3">
        <el-input
          v-model="query.keyword"
          placeholder="用户名/昵称"
          clearable
          class="w-60!"
          @keyup.enter="loadData"
        />
        <div>
          <el-button @click="loadData">搜索</el-button>
          <el-button type="primary" @click="openCreate">新增用户</el-button>
        </div>
      </div>

      <el-table v-loading="loading" :data="tableData" border>
        <el-table-column prop="username" label="用户名" min-width="120" />
        <el-table-column prop="nickname" label="昵称" min-width="120" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'">
              {{ row.status === 1 ? "启用" : "停用" }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" min-width="160" />
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)"
              >编辑</el-button
            >
            <el-button
              link
              type="warning"
              @click="
                resetTarget = row;
                resetForm.newPassword = '';
                resetVisible = true;
              "
            >
              重置密码
            </el-button>
            <el-button
              link
              :type="row.status === 1 ? 'danger' : 'success'"
              @click="toggleStatus(row)"
            >
              {{ row.status === 1 ? "停用" : "启用" }}
            </el-button>
            <el-popconfirm title="确认删除该用户？" @confirm="doDelete(row)">
              <template #reference>
                <el-button link type="danger">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.pageNo"
        v-model:page-size="query.pageSize"
        :total="total"
        layout="total, prev, pager, next"
        class="mt-3 justify-end"
        @current-change="loadData"
      />
    </el-card>

    <!-- 新增/编辑 -->
    <el-dialog
      v-model="dialogVisible"
      :title="editId == null ? '新增用户' : '编辑用户'"
      width="480px"
    >
      <el-form label-width="90px">
        <el-form-item label="用户名">
          <el-input
            v-model="form.username"
            :disabled="editId != null"
            placeholder="不超过 32 位，支持中文"
          />
        </el-form-item>
        <el-form-item label="昵称">
          <el-input v-model="form.nickname" />
        </el-form-item>
        <el-form-item v-if="editId == null" label="初始密码">
          <el-input
            v-model="form.password"
            show-password
            placeholder="6-64 位"
          />
        </el-form-item>
        <el-form-item label="角色">
          <el-select
            v-model="form.roleIds"
            multiple
            placeholder="选择角色"
            class="w-full"
          >
            <el-option
              v-for="r in roleOptions"
              :key="r.id"
              :label="r.name"
              :value="r.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 重置密码 -->
    <el-dialog
      v-model="resetVisible"
      :title="`重置密码：${resetTarget?.username}`"
      width="420px"
    >
      <el-input
        v-model="resetForm.newPassword"
        show-password
        placeholder="新密码（6-64 位）"
      />
      <template #footer>
        <el-button @click="resetVisible = false">取消</el-button>
        <el-button type="primary" @click="doResetPassword">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>
