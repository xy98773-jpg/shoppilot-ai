<template>
  <div class="catalog-page">
    <el-card shadow="never">
      <template #header>
        <div class="catalog-page__head">
          <span class="catalog-page__title">SKU</span>
          <el-button v-if="canWrite" type="primary" @click="openCreate">新建 SKU</el-button>
        </div>
      </template>

      <SearchForm :fields="searchFields" @search="onSearch" @reset="onReset" />

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
      :title="editingId === null ? '新建 SKU' : '编辑 SKU'"
      width="620px"
      @closed="onDialogClosed"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="120px">
        <el-form-item label="所属商品 ID" prop="product_id">
          <!-- ★ product_id 不可变：编辑时禁用（服务端亦忽略请求体中的 product_id）。 -->
          <el-input-number
            v-model="form.product_id"
            :min="1"
            :precision="0"
            :disabled="editingId !== null"
          />
          <span v-if="editingId === null" class="catalog-page__hint">
            须为本团队商品 ID；也可从「商品」页进入该商品下新建。
          </span>
        </el-form-item>
        <el-form-item label="SKU 编码" prop="sku_code">
          <el-input v-model="form.sku_code" maxlength="64" placeholder="团队内唯一，如 SKU-BAG-001-BLK" />
        </el-form-item>
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" maxlength="128" />
        </el-form-item>
        <el-form-item label="条码">
          <el-input v-model="form.barcode" maxlength="64" />
        </el-form-item>
        <el-form-item label="供应商 ID">
          <el-input-number v-model="form.supplier_id" :min="1" :precision="0" />
        </el-form-item>
        <el-form-item label="交期(天)">
          <el-input-number v-model="form.default_lead_time_days" :min="0" :precision="0" />
        </el-form-item>
        <el-form-item label="最小起订量">
          <el-input v-model="form.moq" placeholder="字符串，如 50.000" />
        </el-form-item>
        <el-form-item label="采购倍数">
          <el-input v-model="form.order_multiple" placeholder="字符串，如 10.000" />
        </el-form-item>
        <el-form-item label="数量步长">
          <el-input v-model="form.qty_step" placeholder="字符串，如 1.000" />
        </el-form-item>
        <el-form-item label="采购价">
          <el-input v-model="form.purchase_price" placeholder="字符串，如 35.5000" />
        </el-form-item>
        <el-form-item label="销售价">
          <el-input v-model="form.sale_price" placeholder="字符串，如 89.0000" />
        </el-form-item>
        <el-form-item label="上架日期">
          <el-date-picker v-model="form.listed_date" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" />
        </el-form-item>
        <p class="catalog-page__note">
          说明：条码 / 采购价 / 销售价 未包含在 SKU 列表响应中，编辑时留空即**不修改**该字段。
        </p>
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
// SKU 列表（契约 §10.3 的 skus 6 端点：list / create / get / update / delete / status）。
// ★ SKU 有 `PATCH /{id}/status`（启用/停用）与 `DELETE /{id}`（软删）。
// ★ 编辑时 `product_id` 不可变（服务端忽略请求体中的 product_id，换商品须新建 SKU）。
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import PageTable from '@/components/common/PageTable.vue'
import SearchForm from '@/components/common/SearchForm.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import { changeSkuStatus, createSku, deactivateSku, listSkus, updateSku } from '@/api/catalog'
import type { Sku, SkuCreateInput, SkuQuery, SkuUpdateInput } from '@/types/catalog'
import type { SearchField } from '@/types/ui'
import { usePagination } from '@/composables/usePagination'
import { useUserStore } from '@/stores/user'
import { hasPermission } from '@/utils/permission'
import { describeError } from '@/utils/error'
import { pruneEmpty } from '@/utils/query'
import { formatDateTime } from '@/utils/format'

const userStore = useUserStore()
const canWrite = computed(() => hasPermission(userStore.permissions, 'master_data_manage'))

const searchFields: SearchField[] = [
  { prop: 'keyword', label: '关键字', type: 'input', width: 180 },
  { prop: 'productId', label: '商品 ID', type: 'input', width: 120 },
  {
    prop: 'status',
    label: '状态',
    type: 'select',
    width: 140,
    options: [
      { label: '启用', value: 'ACTIVE' },
      { label: '停用', value: 'INACTIVE' },
    ],
  },
]

const columns = [
  { prop: 'sku_code', label: 'SKU 编码', minWidth: 160 },
  { prop: 'name', label: '名称', minWidth: 150 },
  { prop: 'status', label: '状态', width: 90, slot: 'status' },
  { prop: 'product_id', label: '商品 ID', width: 100 },
  { prop: 'supplier_id', label: '供应商 ID', width: 110 },
  { prop: 'default_lead_time_days', label: '交期(天)', width: 100 },
  { prop: 'moq', label: '最小起订量', minWidth: 110 },
  { prop: 'order_multiple', label: '采购倍数', minWidth: 110 },
  { prop: 'qty_step', label: '数量步长', minWidth: 100 },
  { prop: 'listed_date', label: '上架日期', minWidth: 120 },
  { prop: 'created_at', label: '创建时间', minWidth: 170, slot: 'createdAt' },
]

const { list, total, loading, query, load, changePage, changeSize } = usePagination<Sku>((q) =>
  listSkus(pruneEmpty(q) as SkuQuery),
)

/** 搜索：把筛选条件写回分页 query 并回到第 1 页（商品 ID 由字符串安全转数字）。 */
function onSearch(params: Record<string, unknown>): void {
  const raw = params.productId
  const productId = typeof raw === 'string' && raw.trim() !== '' ? Number(raw) : undefined
  query.productId = productId !== undefined && !Number.isNaN(productId) ? productId : undefined
  query.status = params.status
  query.keyword = params.keyword
  query.page = 1
  void load()
}

function onReset(): void {
  query.productId = undefined
  query.status = undefined
  query.keyword = undefined
  query.page = 1
  void load()
}

interface SkuForm {
  product_id: number | null
  sku_code: string
  name: string
  barcode: string
  supplier_id: number | null
  default_lead_time_days: number | null
  moq: string
  order_multiple: string
  qty_step: string
  purchase_price: string
  sale_price: string
  listed_date: string
}

function emptyForm(): SkuForm {
  return {
    product_id: null,
    sku_code: '',
    name: '',
    barcode: '',
    supplier_id: null,
    default_lead_time_days: null,
    moq: '',
    order_multiple: '',
    qty_step: '',
    purchase_price: '',
    sale_price: '',
    listed_date: '',
  }
}

const dialogVisible = ref(false)
const submitting = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance>()
const confirmRef = ref<InstanceType<typeof ConfirmDialog> | null>(null)
const form = reactive<SkuForm>(emptyForm())

const rules: FormRules<SkuForm> = {
  product_id: [{ required: true, message: '请输入所属商品 ID', trigger: 'blur' }],
  sku_code: [{ required: true, message: '请输入 SKU 编码', trigger: 'blur' }],
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }],
}

function openCreate(): void {
  editingId.value = null
  Object.assign(form, emptyForm())
  dialogVisible.value = true
}

function openEdit(row: Sku): void {
  editingId.value = row.id
  Object.assign(form, {
    product_id: row.product_id,
    sku_code: row.sku_code ?? '',
    name: row.name ?? '',
    // 以下三项不在列表响应中，无法回填 → 留空即不修改（见下方 payload 组装说明）
    barcode: '',
    supplier_id: row.supplier_id,
    default_lead_time_days: row.default_lead_time_days,
    moq: row.moq ?? '',
    order_multiple: row.order_multiple ?? '',
    qty_step: row.qty_step ?? '',
    purchase_price: '',
    sale_price: '',
    listed_date: row.listed_date ?? '',
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

  submitting.value = true
  try {
    if (editingId.value === null) {
      const payload: SkuCreateInput = {
        product_id: form.product_id ?? undefined,
        sku_code: form.sku_code.trim(),
        name: form.name.trim(),
        barcode: form.barcode.trim() || null,
        supplier_id: form.supplier_id,
        default_lead_time_days: form.default_lead_time_days,
        moq: form.moq.trim() || null,
        order_multiple: form.order_multiple.trim() || null,
        qty_step: form.qty_step.trim() || null,
        purchase_price: form.purchase_price.trim() || null,
        sale_price: form.sale_price.trim() || null,
        listed_date: form.listed_date || null,
      }
      await createSku(payload)
      ElMessage.success('SKU 已创建')
    } else {
      const patch: SkuUpdateInput = {
        sku_code: form.sku_code.trim(),
        name: form.name.trim(),
        supplier_id: form.supplier_id,
        default_lead_time_days: form.default_lead_time_days,
        moq: form.moq.trim() || null,
        order_multiple: form.order_multiple.trim() || null,
        qty_step: form.qty_step.trim() || null,
        listed_date: form.listed_date || null,
      }
      // barcode / purchase_price / sale_price 不在列表响应中（无法回填）→ 仅在用户填写时提交，
      // 避免「一进编辑页就把既有值清空」的破坏性副作用。
      if (form.barcode.trim()) patch.barcode = form.barcode.trim()
      if (form.purchase_price.trim()) patch.purchase_price = form.purchase_price.trim()
      if (form.sale_price.trim()) patch.sale_price = form.sale_price.trim()

      await updateSku(editingId.value, patch)
      ElMessage.success('SKU 已更新')
    }
    dialogVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    submitting.value = false
  }
}

async function onDisable(row: Sku): Promise<void> {
  const ok = await confirmRef.value?.open({
    title: '停用 SKU',
    message: `确定停用 SKU「${row.name}」？停用为软删（行保留），可重新启用。`,
  })
  if (!ok) return
  try {
    await deactivateSku(row.id)
    ElMessage.success('SKU 已停用')
    await load()
  } catch (error) {
    ElMessage.error(describeError(error))
  }
}

async function onEnable(row: Sku): Promise<void> {
  const ok = await confirmRef.value?.open({
    title: '启用 SKU',
    message: `确定启用 SKU「${row.name}」？`,
  })
  if (!ok) return
  try {
    await changeSkuStatus(row.id, 'ACTIVE')
    ElMessage.success('SKU 已启用')
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
.catalog-page__note {
  margin: 0;
  padding-left: 120px;
  color: #909399;
  font-size: 12px;
  line-height: 1.6;
}
</style>
