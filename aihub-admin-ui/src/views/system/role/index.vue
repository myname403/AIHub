<script setup lang="ts">
import { ref, reactive, onMounted, nextTick } from "vue";
import { message } from "@/utils/message";
import { http } from "@/utils/http";
import type { ElTree } from "element-plus";

defineOptions({ name: "SystemRole" });

interface RoleRow {
  id: number;
  code: string;
  name: string;
  sort: number;
  status: number;
  remark: string;
}

const loading = ref(false);
const tableData = ref<RoleRow[]>([]);
const total = ref(0);
const query = reactive({ pageNo: 1, pageSize: 10, keyword: "" });

const dialogVisible = ref(false);
const editId = ref<number | null>(null);
const form = reactive({ code: "", name: "", sort: 1, remark: "" });

/** 菜单授权弹窗 */
const assignVisible = ref(false);
const assignTarget = ref<RoleRow | null>(null);
const treeRef = ref<InstanceType<typeof ElTree>>();
const menuTree = ref<any[]>([]);
const defaultProps = { label: "name", children: "children" };

const loadData = async () => {
  loading.value = true;
  try {
    const page: any = await http.request(
      "get",
      "/api/platform/system/role/page",
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

const openCreate = () => {
  editId.value = null;
  Object.assign(form, { code: "", name: "", sort: 1, remark: "" });
  dialogVisible.value = true;
};

const openEdit = (row: RoleRow) => {
  editId.value = row.id;
  Object.assign(form, {
    code: row.code,
    name: row.name,
    sort: row.sort,
    remark: row.remark
  });
  dialogVisible.value = true;
};

const submit = async () => {
  if (editId.value == null) {
    await http.request("post", "/api/platform/system/role", {
      data: { ...form }
    });
    message("新增成功", { type: "success" });
  } else {
    await http.request("put", `/api/platform/system/role/${editId.value}`, {
      data: { name: form.name, sort: form.sort, remark: form.remark }
    });
    message("修改成功", { type: "success" });
  }
  dialogVisible.value = false;
  loadData();
};

/** 打开菜单授权弹窗：拉全量菜单树 + 该角色已勾选的菜单 ID */
const openAssign = async (row: RoleRow) => {
  assignTarget.value = row;
  const tree: any = await http.request("get", "/api/platform/system/menu/tree");
  // MenuNode{menu, children} → el-tree 需要的 {id, name, children}
  menuTree.value = flatten(tree);
  const checkedIds: number[] = await http.request(
    "get",
    `/api/platform/system/role/${row.id}/menu-ids`
  );
  assignVisible.value = true;
  // 等树渲染完再回显勾选：只勾"叶子"（F/C 无子节点），父节点由 el-tree 的父子联动自动补齐
  await nextTick();
  const leafIds = collectLeafIds(menuTree.value);
  treeRef.value?.setCheckedKeys(
    checkedIds.filter((id: number) => leafIds.has(id))
  );
};

const doAssign = async () => {
  // 勾选 + 半选的父节点都要提交（否则父菜单路径不会授权）
  const checked = treeRef.value?.getCheckedKeys() ?? [];
  const half = treeRef.value?.getHalfCheckedKeys() ?? [];
  await http.request(
    "put",
    `/api/platform/system/role/${assignTarget.value?.id}/assign-menus`,
    { data: { menuIds: [...half, ...checked] } }
  );
  message("授权已保存", { type: "success" });
  assignVisible.value = false;
};

/** MenuNode 树（平铺结构）→ el-tree 数据 */
const flatten = (nodes: any[]): any[] =>
  (nodes ?? []).map(n => ({
    id: n.id,
    name: `${n.name}（${n.menuType}）`,
    children: flatten(n.children)
  }));

const collectLeafIds = (nodes: any[], set = new Set<number>()): Set<number> => {
  for (const n of nodes) {
    if (!n.children || n.children.length === 0) set.add(n.id);
    else collectLeafIds(n.children, set);
  }
  return set;
};

const doDelete = async (row: RoleRow) => {
  await http.request("delete", `/api/platform/system/role/${row.id}`);
  message("已删除", { type: "success" });
  loadData();
};

onMounted(loadData);
</script>

<template>
  <div class="p-4">
    <el-card shadow="never">
      <div class="flex justify-between mb-3">
        <el-input
          v-model="query.keyword"
          placeholder="角色名/编码"
          clearable
          class="w-60!"
          @keyup.enter="loadData"
        />
        <div>
          <el-button @click="loadData">搜索</el-button>
          <el-button type="primary" @click="openCreate">新增角色</el-button>
        </div>
      </div>

      <el-table v-loading="loading" :data="tableData" border>
        <el-table-column prop="name" label="角色名称" min-width="120" />
        <el-table-column prop="code" label="角色编码" min-width="160" />
        <el-table-column prop="sort" label="顺序" width="80" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'">
              {{ row.status === 1 ? "启用" : "停用" }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          prop="remark"
          label="备注"
          min-width="160"
          show-overflow-tooltip
        />
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openAssign(row)"
              >菜单授权</el-button
            >
            <el-button link type="primary" @click="openEdit(row)"
              >编辑</el-button
            >
            <el-popconfirm
              title="删除后绑定该角色的用户将失去对应权限，确认？"
              @confirm="doDelete(row)"
            >
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
      :title="editId == null ? '新增角色' : '编辑角色'"
      width="480px"
    >
      <el-form label-width="90px">
        <el-form-item label="角色编码">
          <el-input
            v-model="form.code"
            :disabled="editId != null"
            placeholder="ROLE_ 前缀，如 ROLE_OPERATOR"
          />
        </el-form-item>
        <el-form-item label="角色名称">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="顺序">
          <el-input-number v-model="form.sort" :min="0" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 菜单授权 -->
    <el-dialog
      v-model="assignVisible"
      :title="`菜单授权：${assignTarget?.name}`"
      width="460px"
    >
      <el-tree
        ref="treeRef"
        :data="menuTree"
        :props="defaultProps"
        show-checkbox
        node-key="id"
        default-expand-all
      />
      <template #footer>
        <el-button @click="assignVisible = false">取消</el-button>
        <el-button type="primary" @click="doAssign">保存授权</el-button>
      </template>
    </el-dialog>
  </div>
</template>
