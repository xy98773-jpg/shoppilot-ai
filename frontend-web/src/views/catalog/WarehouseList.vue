<template>
  <div class="catalog-page">
    <el-card shadow="never">
      <template #header>
        <div class="catalog-page__head">
          <span class="catalog-page__title">仓库</span>
          <el-button v-if="canWrite" type="primary" @click="openCreate">新建仓库</el-button>
        </div>
      </template>

      <PageTable
        :columns="columns"
        :rows="list"
        :loading="loading"
        :total="total"
        :page="query.page"
        :page-size="query.pageSize"
        @page-change="changePage"
        @size-change="changeSize"
      >
        <template #status="{ row }">
          <StatusTag :status="row.status" />
        </template>
        <template #isDefault="{ row }">
          <el-tag v-if="row.is_default" type="success" size="small">默认</el-tag>
          <span v-else>—</span>
        </template>
        <template #createdAt="{ row }">{{ formatDateTime(row.created_at) }}</template>
        <template #actions="{ row }">
          <template v-if="canWrite">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="row.status === 'ACTIVE'" link type="warning" @click="onDisable(row)">
              停用
            </el-button>
            <el-button v-else link type="success" @click="onEnable(row)">启用</el-button>
          </template>
          <span v-else>—</span>
        </template>
      </PageTable>
    </el-card>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId === null ? '新建仓库' : '编辑仓库'"
      width="520px"
      @closed="onDialogClosed"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="编码" prop="code">
          <el-input v-model="form.code" maxlength="64" placeholder="团队内唯一，如 WH-001" />
        </el-form-item>
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" maxlength="128" />
        </el-form-item>
        <el-form-item label="地址">
          <el-input v-model="form.address" maxlength="255" />
        </el-form-item>
        <el-form-item label="联系人">
          <el-input v-model="form.contact_name" maxlength="64" />
        </el-form-item>
        <el-form-item label="联系电话">
          <el-input v-model="form.contact_phone" maxlength="32" />
        </el-form-item>
        <el-form-item label="设为默认仓">
          <el-switch v-model="form.is_default" />
          <span class="catalog-page__hint">同团队默认仓唯一，开启后会自动取消其它仓的默认标记。</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="500" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="onSubmit">保存</el-button>
      </template>
    </el-dialog>

    <ConfirmDialog ref="confirmRef" />
  </div>
</template>

<script setup lang="ts">
// 仓库列表（契约 §10.3 的 warehouses 6 端点：list / create / get / update / delete / status）。
//
// 红线：
// - 仅调用 `api/catalog.ts`，不直接 import axios；不 import stores 以外的东西做权限判定；
// - 写按钮按 `MASTER_DATA_MANAGE` 能力位（来自 /auth/me）**体验层**显隐，服务端仍是唯一边界；
// - 不承载业务规则（默认仓唯一性、停用前置校验均由服务端保证）。
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import PageTable from '@/components/common/PageTable.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import { createWarehouse, deactivateWarehouse, changeWarehouseStatus, listWarehouses, updateWarehouse } from '@/api/catalog'
import type { Warehouse, WarehouseCreateInput, WarehouseQuery } from '@/types/catalog'
import { usePagination } from '@/composables/usePagination'
import { useUserStore } from '@/stores/user'
import { hasPermission } from '@/utils/permission'
import { describeError } from '@/utils/error'
import { pruneEmpty } from '@/utils/query'
import { formatDateTime } from '@/utils/format'

const userStore = useUserStore()
/** 主数据写权限位（USER_ROLES §2.2 #2）；仅用于按钮显隐。 */
const canWrite = computed(() => hasPermission(userStore.permissions, 'master_data_manage'))

const columns = [
  { prop: 'code', label: '编码', minWidth: 140 },
  { prop: 'name', label: '名称', minWidth: 160 },
  { prop: 'status', label: '状态', width: 90, slot: 'status' },
  { prop: 'is_default', label: '默认仓', width: 90, slot: 'isDefault' },
  { prop: 'contact_name', label: '联系人', minWidth: 110 },
  { prop: 'contact_phone', label: '联系电话', minWidth: 130 },
  { prop: 'created_at', label: '创建时间', minWidth: 170, slot: 'createdAt' },
]

const { list, total, loading, query, load, changePage, changeSize } = usePagination<Warehouse>((q) =>
  listWarehouses(pruneEmpty(q) as WarehouseQuery),
)

// ---------------------------------------------------------------------------
// 新建 / 编辑
// ---------------------------------------------------------------------------

interface WarehouseForm {
  code: string
  name: string
  address: string
  contact_name: string
  contact_phone: string
  remark: string
  is_default: boolean
}

function emptyForm(): WarehouseForm {
  return {
    code: '',
    name: '',
    address: '',
    contact_name: '',
    contact_phone: '',
    remark: '',
    is_default: false,
  }
}

const dialogVisible = ref(false)
const submitting = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance>()
const confirmRef = ref<InstanceType<typeof ConfirmDialog> | null>(null)
const form = reactive<WarehouseForm>(emptyForm())

const rules: FormRules<WarehouseForm> = {
  code: [{ required: true, message: '请输入编码', trigger: 'blur' }],
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }],
}

function openCreate(): void {
  editingId.value = null
  Object.assign(form, emptyForm())
  dialogVisible.value = true
}

function openEdit(row: Warehouse): void {
  editingId.value = row.id
  Object.assign(form, {
    code: row.code ?? '',
    name: row.name ?? '',
    address: row.address ?? '',
    contact_name: row.contact_name ?? '',
    contact_phone: row.contact_phone ?? '',
    remark: row.remark ?? '',
    is_default: !!row.is_default,
  })
  dialogVisible.value = true
}

function onDialogClosed(): void {
  editingId.value = null
  Object.assign(form, emptyForm())
  formRef.value?.clearValidate()
}

async function onSubmit(): Promise<void> {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  const payload: WarehouseCreateInput = {
    code: form.code.trim(),
    name: form.name.trim(),
    address: form.address.trim() || null,
    contact_name: form.contact_name.trim() || null,
    contact_phone: form.contact_phone.trim() || null,
    remark: form.remark.trim() || null,
    is_default: form.is_default,
  }

  submitting.value = true
  try {
    if (editingId.value === null) {
      await createWarehouse(payload)
      ElMessage.success('仓库已创建')
    } else {
      await updateWarehouse(editingId.value, payload)
      ElMessage.success('仓库已更新')
    }
    dialogVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    submitting.value = false
  }
}

// ---------------------------------------------------------------------------
// 停用 / 启用
// ---------------------------------------------------------------------------

async function onDisable(row: Warehouse): Promise<void> {
  const ok = await confirmRef.value?.open({
    title: '停用仓库',
    message: `确定停用仓库「${row.name}」？停用为软删（行保留），可在列表中重新启用。`,
  })
  if (!ok) return
  try {
    // 停用走 DELETE（契约 §10.3 F-CAT-05，软删 → INACTIVE）
    await deactivateWarehouse(row.id)
    ElMessage.success('仓库已停用')
    await load()
  } catch (error) {
    ElMessage.error(describeError(error))
  }
}

async function onEnable(row: Warehouse): Promise<void> {
  const ok = await confirmRef.value?.open({
    title: '启用仓库',
    message: `确定启用仓库「${row.name}」？`,
  })
  if (!ok) return
  try {
    // 启用走 PATCH /{id}/status（契约 §10.3，仅 warehouses / skus 有此端点）
    await changeWarehouseStatus(row.id, 'ACTIVE')
    ElMessage.success('仓库已启用')
    await load()
  } catch (error) {
    ElMessage.error(describeError(error))
  }
}

onMounted(() => {
  void load()
})
</script>

<style scoped>
.catalog-page__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.catalog-page__title {
  font-weight: 600;
}
.catalog-page__hint {
  margin-left: 12px;
  color: #909399;
  font-size: 12px;
}
</style>
