<template>
  <div class="catalog-page">
    <el-card shadow="never">
      <template #header>
        <div class="catalog-page__head">
          <span class="catalog-page__title">商品</span>
          <el-button v-if="canWrite" type="primary" @click="openCreate">新建商品</el-button>
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
            <!-- ★ 契约 §10.3 未定义 products 的 PATCH /{id}/status，故无「启用」按钮。 -->
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
      :title="editingId === null ? '新建商品' : '编辑商品'"
      width="560px"
      @closed="onDialogClosed"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="商品编码" prop="product_code">
          <el-input v-model="form.product_code" maxlength="64" placeholder="团队内唯一，如 P-BAG-001" />
        </el-form-item>
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" maxlength="128" />
        </el-form-item>
        <el-form-item label="分类">
          <el-input v-model="form.category" maxlength="64" />
        </el-form-item>
        <el-form-item label="品牌">
          <el-input v-model="form.brand" maxlength="64" />
        </el-form-item>
        <el-form-item label="单位">
          <el-input v-model="form.unit" maxlength="16" />
        </el-form-item>
        <el-form-item label="默认供应商">
          <el-input-number v-model="form.default_supplier_id" :min="1" :precision="0" />
          <span class="catalog-page__hint">须为本团队供应商 ID（跨团队由服务端拒绝）。</span>
        </el-form-item>
        <el-form-item label="规格摘要">
          <el-input v-model="form.spec_summary" maxlength="255" />
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
// 商品列表（契约 §10.3 的 products 7 端点，含商品下 SKU 子资源；页面仅用 list/create/update/delete）。
// ★ 商品**没有** `PATCH /{id}/status`，故仅提供「停用」（DELETE 软删）。
// ★ 停用商品时若其下仍有 ACTIVE SKU，服务端返回 422 `CAT-3009`，此处如实展示该错误码。
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import PageTable from '@/components/common/PageTable.vue'
import SearchForm from '@/components/common/SearchForm.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import { createProduct, deactivateProduct, listProducts, updateProduct } from '@/api/catalog'
import type { Product, ProductCreateInput, ProductQuery, ProductUpdateInput } from '@/types/catalog'
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
  { prop: 'category', label: '分类', type: 'input', width: 160 },
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
  { prop: 'product_code', label: '商品编码', minWidth: 150 },
  { prop: 'name', label: '名称', minWidth: 160 },
  { prop: 'category', label: '分类', minWidth: 100 },
  { prop: 'brand', label: '品牌', minWidth: 100 },
  { prop: 'unit', label: '单位', width: 80 },
  { prop: 'status', label: '状态', width: 90, slot: 'status' },
  { prop: 'default_supplier_id', label: '默认供应商', minWidth: 110 },
  { prop: 'created_at', label: '创建时间', minWidth: 170, slot: 'createdAt' },
]

const { list, total, loading, query, load, changePage, changeSize } = usePagination<Product>((q) =>
  listProducts(pruneEmpty(q) as ProductQuery),
)

/** 搜索：把筛选条件写回分页 query 并回到第 1 页。 */
function onSearch(params: Record<string, unknown>): void {
  query.category = params.category
  query.status = params.status
  query.page = 1
  void load()
}

function onReset(): void {
  query.category = undefined
  query.status = undefined
  query.page = 1
  void load()
}

interface ProductForm {
  product_code: string
  name: string
  category: string
  brand: string
  unit: string
  default_supplier_id: number | null
  spec_summary: string
  remark: string
}

function emptyForm(): ProductForm {
  return {
    product_code: '',
    name: '',
    category: '',
    brand: '',
    unit: '',
    default_supplier_id: null,
    spec_summary: '',
    remark: '',
  }
}

const dialogVisible = ref(false)
const submitting = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance>()
const confirmRef = ref<InstanceType<typeof ConfirmDialog> | null>(null)
const form = reactive<ProductForm>(emptyForm())

const rules: FormRules<ProductForm> = {
  product_code: [{ required: true, message: '请输入商品编码', trigger: 'blur' }],
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }],
}

function openCreate(): void {
  editingId.value = null
  Object.assign(form, emptyForm())
  dialogVisible.value = true
}

function openEdit(row: Product): void {
  editingId.value = row.id
  Object.assign(form, {
    product_code: row.product_code ?? '',
    name: row.name ?? '',
    category: row.category ?? '',
    brand: row.brand ?? '',
    unit: row.unit ?? '',
    default_supplier_id: row.default_supplier_id,
    spec_summary: row.spec_summary ?? '',
    remark: '',
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

  const payload: ProductCreateInput = {
    product_code: form.product_code.trim(),
    name: form.name.trim(),
    category: form.category.trim() || null,
    brand: form.brand.trim() || null,
    unit: form.unit.trim() || null,
    default_supplier_id: form.default_supplier_id,
    spec_summary: form.spec_summary.trim() || null,
    remark: form.remark.trim() || null,
  }

  submitting.value = true
  try {
    if (editingId.value === null) {
      await createProduct(payload)
      ElMessage.success('商品已创建')
    } else {
      // `ProductView` **不回传** `remark`（契约 §11.2 商品响应无该字段）→ 无法回填。
      // 故编辑时若用户未填写备注则不提交该字段，避免把既有备注误清空。
      const patch: ProductUpdateInput = { ...payload }
      if (!patch.remark) {
        delete patch.remark
      }
      await updateProduct(editingId.value, patch)
      ElMessage.success('商品已更新')
    }
    dialogVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    submitting.value = false
  }
}

async function onDisable(row: Product): Promise<void> {
  const ok = await confirmRef.value?.open({
    title: '停用商品',
    message: `确定停用商品「${row.name}」？若其下仍有启用的 SKU，服务端将拒绝（CAT-3009）。`,
  })
  if (!ok) return
  try {
    await deactivateProduct(row.id)
    ElMessage.success('商品已停用')
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
