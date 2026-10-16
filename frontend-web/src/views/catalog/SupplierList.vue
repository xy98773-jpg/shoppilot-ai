<template>
  <div class="catalog-page">
    <el-card shadow="never">
      <template #header>
        <div class="catalog-page__head">
          <span class="catalog-page__title">供应商</span>
          <el-button v-if="canWrite" type="primary" @click="openCreate">新建供应商</el-button>
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
        <template #createdAt="{ row }">{{ formatDateTime(row.created_at) }}</template>
        <template #actions="{ row }">
          <template v-if="canWrite">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <!-- ★ 契约 §10.3 未定义 suppliers 的 PATCH /{id}/status，故无「启用」按钮（不得"补全"）。 -->
            <el-button v-if="row.status === 'ACTIVE'" link type="warning" @click="onDisable(row)">
              停用
            </el-button>
          </template>
          <span v-else>—</span>
        </template>
      </PageTable>
    </el-card>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId === null ? '新建供应商' : '编辑供应商'"
      width="560px"
      @closed="onDialogClosed"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="编码" prop="code">
          <el-input v-model="form.code" maxlength="64" placeholder="团队内唯一，如 SUP-001" />
        </el-form-item>
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" maxlength="128" />
        </el-form-item>
        <el-form-item label="联系人">
          <el-input v-model="form.contact_name" maxlength="64" />
        </el-form-item>
        <el-form-item label="联系电话">
          <el-input v-model="form.contact_phone" maxlength="32" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="form.email" maxlength="128" />
        </el-form-item>
        <el-form-item label="地址">
          <el-input v-model="form.address" maxlength="255" />
        </el-form-item>
        <el-form-item label="交期(天)">
          <el-input-number v-model="form.lead_time_days" :min="0" :precision="0" />
        </el-form-item>
        <el-form-item label="最小起订量">
          <el-input v-model="form.moq" placeholder="数量以字符串传输，如 50.000" />
        </el-form-item>
        <el-form-item label="采购倍数">
          <el-input v-model="form.order_multiple" placeholder="须大于 0，如 10.000" />
        </el-form-item>
        <el-form-item label="付款条件">
          <el-input v-model="form.payment_terms" maxlength="128" />
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
// 供应商列表（契约 §10.3 的 suppliers 5 端点：list / create / get / update / delete）。
// ★ 供应商**没有** `PATCH /{id}/status`，故仅提供「停用」（DELETE 软删），不提供「启用」。
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import PageTable from '@/components/common/PageTable.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import {
  createSupplier,
  deactivateSupplier,
  listSuppliers,
  updateSupplier,
} from '@/api/catalog'
import type { Supplier, SupplierCreateInput, SupplierQuery } from '@/types/catalog'
import { usePagination } from '@/composables/usePagination'
import { useUserStore } from '@/stores/user'
import { hasPermission } from '@/utils/permission'
import { describeError } from '@/utils/error'
import { pruneEmpty } from '@/utils/query'
import { formatDateTime } from '@/utils/format'

const userStore = useUserStore()
const canWrite = computed(() => hasPermission(userStore.permissions, 'master_data_manage'))

const columns = [
  { prop: 'code', label: '编码', minWidth: 130 },
  { prop: 'name', label: '名称', minWidth: 150 },
  { prop: 'status', label: '状态', width: 90, slot: 'status' },
  { prop: 'contact_name', label: '联系人', minWidth: 100 },
  { prop: 'contact_phone', label: '联系电话', minWidth: 130 },
  { prop: 'email', label: '邮箱', minWidth: 170 },
  { prop: 'lead_time_days', label: '交期(天)', width: 100 },
  { prop: 'moq', label: '最小起订量', minWidth: 110 },
  { prop: 'order_multiple', label: '采购倍数', minWidth: 110 },
  { prop: 'created_at', label: '创建时间', minWidth: 170, slot: 'createdAt' },
]

const { list, total, loading, query, load, changePage, changeSize } = usePagination<Supplier>((q) =>
  listSuppliers(pruneEmpty(q) as SupplierQuery),
)

interface SupplierForm {
  code: string
  name: string
  contact_name: string
  contact_phone: string
  email: string
  address: string
  lead_time_days: number | null
  moq: string
  order_multiple: string
  payment_terms: string
  remark: string
}

function emptyForm(): SupplierForm {
  return {
    code: '',
    name: '',
    contact_name: '',
    contact_phone: '',
    email: '',
    address: '',
    lead_time_days: null,
    moq: '',
    order_multiple: '',
    payment_terms: '',
    remark: '',
  }
}

const dialogVisible = ref(false)
const submitting = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance>()
const confirmRef = ref<InstanceType<typeof ConfirmDialog> | null>(null)
const form = reactive<SupplierForm>(emptyForm())

const rules: FormRules<SupplierForm> = {
  code: [{ required: true, message: '请输入编码', trigger: 'blur' }],
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }],
}

function openCreate(): void {
  editingId.value = null
  Object.assign(form, emptyForm())
  dialogVisible.value = true
}

function openEdit(row: Supplier): void {
  editingId.value = row.id
  Object.assign(form, {
    code: row.code ?? '',
    name: row.name ?? '',
    contact_name: row.contact_name ?? '',
    contact_phone: row.contact_phone ?? '',
    email: row.email ?? '',
    address: row.address ?? '',
    lead_time_days: row.lead_time_days,
    moq: row.moq ?? '',
    order_multiple: row.order_multiple ?? '',
    payment_terms: row.payment_terms ?? '',
    remark: row.remark ?? '',
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

  const payload: SupplierCreateInput = {
    code: form.code.trim(),
    name: form.name.trim(),
    contact_name: form.contact_name.trim() || null,
    contact_phone: form.contact_phone.trim() || null,
    email: form.email.trim() || null,
    address: form.address.trim() || null,
    lead_time_days: form.lead_time_days,
    moq: form.moq.trim() || null,
    order_multiple: form.order_multiple.trim() || null,
    payment_terms: form.payment_terms.trim() || null,
    remark: form.remark.trim() || null,
  }

  submitting.value = true
  try {
    if (editingId.value === null) {
      await createSupplier(payload)
      ElMessage.success('供应商已创建')
    } else {
      await updateSupplier(editingId.value, payload)
      ElMessage.success('供应商已更新')
    }
    dialogVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    submitting.value = false
  }
}

async function onDisable(row: Supplier): Promise<void> {
  const ok = await confirmRef.value?.open({
    title: '停用供应商',
    message: `确定停用供应商「${row.name}」？停用为软删（行保留）。`,
  })
  if (!ok) return
  try {
    await deactivateSupplier(row.id)
    ElMessage.success('供应商已停用')
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
</style>
