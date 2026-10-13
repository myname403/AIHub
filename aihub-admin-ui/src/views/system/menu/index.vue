<script setup lang="ts">
import { ref, reactive, onMounted, nextTick } from "vue";
import { message } from "@/utils/message";
import { http } from "@/utils/http";

defineOptions({ name: "SystemMenu" });

const loading = ref(false);
/** el-table 树形数据：{...menu字段, children} */
const treeData = ref<any[]>([]);

const dialogVisible = ref(false);
const editId = ref<number | null>(null);
const form = reactive({
  parentId: 0,
  name: "",
  permission: "",
  menuType: "C",
  path: "",
  component: "",
  icon: "",
  sort: 1,
  visible: 1,
  status: 1
});

const typeLabel: Record<string, string> = { M: "目录", C: "菜单", F: "按钮" };

/** MenuNode（平铺结构 {id,name,menuType,...,children}）→ el-table 树行 */
const flatten = (nodes: any[]): any[] =>
  (nodes ?? []).map(n => ({
    ...n,
    typeLabel: typeLabel[n.menuType] ?? n.menuType,
    children: flatten(n.children)
  }));

const loadData = async () => {
  loading.value = true;
  try {
    const tree: any = await http.request(
      "get",
      "/api/platform/system/menu/tree"
    );
    treeData.value = flatten(tree);
  } finally {
    loading.value = false;
  }
};

const openCreate = (parentId = 0) => {
  editId.value = null;
  Object.assign(form, {
    parentId,
    name: "",
    permission: "",
    menuType: parentId === 0 ? "M" : "C",
    path: "",
    component: "",
    icon: "",
    sort: 1,
    visible: 1,
    status: 1
  });
  dialogVisible.value = true;
};

const openEdit = (row: any) => {
  editId.value = row.id;
  Object.assign(form, {
    parentId: row.parentId,
    name: row.name,
    permission: row.permission ?? "",
    menuType: row.menuType,
    path: row.path ?? "",
    component: row.component ?? "",
    icon: row.icon ?? "",
    sort: row.sort,
    visible: row.visible,
    status: row.status
  });
  dialogVisible.value = true;
};

const submit = async () => {
  if (editId.value == null) {
    await http.request("post", "/api/platform/system/menu", {
      data: { ...form }
    });
    message("新增成功", { type: "success" });
  } else {
    await http.request("put", `/api/platform/system/menu/${editId.value}`, {
      data: { ...form }
    });
    message("修改成功", { type: "success" });
  }
  dialogVisible.value = false;
  loadData();
};

const doDelete = async (row: any) => {
  await http.request("delete", `/api/platform/system/menu/${row.id}`);
  message("已删除", { type: "success" });
  loadData();
};

onMounted(async () => {
  await nextTick();
  loadData();
});
</script>

<template>
  <div class="p-4">
    <el-card shadow="never">
      <div class="flex justify-between mb-3">
        <span class="text-sm text-gray-500">
          菜单是平台全局数据：M 目录 / C 菜单 / F
          按钮按钮权限。修改后所有用户的权限缓存立即刷新。
        </span>
        <el-button type="primary" @click="openCreate(0)">新增根菜单</el-button>
      </div>

      <el-table
        v-loading="loading"
        :data="treeData"
        row-key="id"
        border
        default-expand-all
        :tree-props="{ children: 'children' }"
      >
        <el-table-column prop="name" label="名称" min-width="160" />
        <el-table-column prop="typeLabel" label="类型" width="80" />
        <el-table-column
          prop="permission"
          label="权限标识"
          min-width="180"
          show-overflow-tooltip
        />
        <el-table-column prop="path" label="路由路径" min-width="130" />
        <el-table-column
          prop="component"
          label="组件"
          min-width="150"
          show-overflow-tooltip
        />
        <el-table-column prop="sort" label="顺序" width="70" />
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.menuType !== 'F'"
              link
              type="primary"
              @click="openCreate(row.id)"
            >
              加子项
            </el-button>
            <el-button link type="primary" @click="openEdit(row)"
              >编辑</el-button
            >
            <el-popconfirm title="确认删除该菜单？" @confirm="doDelete(row)">
              <template #reference>
                <el-button link type="danger">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新增/编辑 -->
    <el-dialog
      v-model="dialogVisible"
      :title="editId == null ? '新增菜单' : '编辑菜单'"
      width="520px"
    >
      <el-form label-width="90px">
        <el-form-item label="菜单类型">
          <el-radio-group v-model="form.menuType" :disabled="editId != null">
            <el-radio value="M">目录</el-radio>
            <el-radio value="C">菜单</el-radio>
            <el-radio value="F">按钮</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="权限标识">
          <el-input
            v-model="form.permission"
            placeholder="如 system:user:create（按钮必填）"
          />
        </el-form-item>
        <el-form-item v-if="form.menuType !== 'F'" label="路由路径">
          <el-input v-model="form.path" placeholder="/system/user" />
        </el-form-item>
        <el-form-item v-if="form.menuType === 'C'" label="组件路径">
          <el-input
            v-model="form.component"
            placeholder="system/user/index（对应 src/views 下文件）"
          />
        </el-form-item>
        <el-form-item label="顺序">
          <el-input-number v-model="form.sort" :min="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>
