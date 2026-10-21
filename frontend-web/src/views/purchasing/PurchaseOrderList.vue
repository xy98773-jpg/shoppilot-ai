<template>
  <div class="po-list" data-testid="po-list">
    <el-card shadow="never">
      <template #header>
        <div class="po-list__head">
          <span class="po-list__title">采购单</span>
          <span class="po-list__subtitle">共 {{ total }} 条</span>
        </div>
      </template>

      <el-form :inline="true" class="po-list__filters" @submit.prevent>
        <el-form-item label="状态">
          <el-select
            v-model="filterStatus"
            clearable
            placeholder="全部"
            style="width: 160px"
            data-testid="po-filter-status"
            @change="onSearch"
          >
            <el-option
              v-for="status in PO_STATUSES"
              :key="status"
              :label="poStatusLabel(status)"
              :value="status"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="供应商 ID">
          <el-input
            v-model="filterSupplierId"
            placeholder="如 9001"
            style="width: 130px"
            data-testid="po-filter-supplier"
            @keyup.enter="onSearch"
          />
        </el-form-item>
        <el-form-item label="仓库 ID">
          <el-input
            v-model="filterWarehouseId"
            placeholder="如 9004"
            style="width: 130px"
            data-testid="po-filter-warehouse"
            @keyup.enter="onSearch"
          />
        </el-form-item>
        <el-form-item label="单号">
          <el-input
            v-model="filterPoNo"
            placeholder="PO 单号关键字"
            style="width: 180px"
            data-testid="po-filter-no"
            @keyup.enter="onSearch"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" data-testid="po-search" @click="onSearch">查询</el-button>
          <el-button data-testid="po-reset" @click="onReset">重置</el-button>
        </el-form-item>
      </el-form>

      <div v-loading="loading">
        <el-table
          v-if="list.length > 0"
          :data="list"
          border
          stripe
          row-key="purchase_order_id"
          data-testid="po-table"
        >
          <el-table-column prop="po_no" label="采购单号" min-width="190" show-overflow-tooltip />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag :type="poStatusTagType(row.status)" size="small">
                {{ poStatusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="供应商" width="100">
            <template #default="{ row }">{{ row.supplier_id ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="仓库" width="100">
            <template #default="{ row }">{{ row.warehouse_id }}</template>
          </el-table-column>
          <el-table-column label="订购总量" width="120" align="right">
            <template #default="{ row }">{{ row.total_qty }}</template>
          </el-table-column>
          <el-table-column label="已收数量" width="120" align="right">
            <template #default="{ row }">{{ row.received_qty }}</template>
          </el-table-column>
          <el-table-column label="总金额" width="130" align="right">
            <template #default="{ row }">{{ row.total_amount }}</template>
          </el-table-column>
          <el-table-column label="创建时间" min-width="170">
            <template #default="{ row }">{{ formatDateTime(row.created_at) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="90" align="center" fixed="right">
            <template #default="{ row }">
              <el-button
                link
                type="primary"
                data-testid="po-row-detail"
                @click="openDetail(row)"
              >
                详情
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-empty
          v-else-if="!loading"
          data-testid="po-empty"
          description="暂无采购单"
          :image-size="90"
        />

        <el-pagination
          v-if="total > 0"
          class="po-list__pager"
          background
          layout="total, sizes, prev, pager, next, jumper"
          :current-page="query.page"
          :page-size="query.pageSize"
          :page-sizes="[10, 20, 50, 100]"
          :total="total"
          data-testid="po-pager"
          @current-change="onPageChange"
          @size-change="onSizeChange"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
// 采购单列表页（M6-T11；`GET /purchasing/orders`）。
//
// ★ 只读页：任何已登录成员可见（路由不加 `meta.roles`），写操作在详情页按角色显隐。
// ★ `total_qty` / `received_qty` / `total_amount` 是后端字符串（scale 3/3/2），
//   原样展示，禁止 `Number()` / 千分位换算（口径铁律 ④）。
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listPurchaseOrders } from '@/api/purchasing'
import type { PurchaseOrderQuery, PurchaseOrderStatus, PurchaseOrderSummary } from '@/types/purchasing'
import { describeError } from '@/utils/error'
import { formatDateTime } from '@/utils/format'
import { toPositiveInt } from '@/views/sales/salesQuery'
import { PO_STATUSES, poStatusLabel, poStatusTagType } from './purchasingStatus'

const router = useRouter()

const list = ref<PurchaseOrderSummary[]>([])
const total = ref(0)
const loading = ref(false)

const query = reactive<PurchaseOrderQuery>({ page: 1, pageSize: 20 })
const filterStatus = ref<PurchaseOrderStatus | ''>('')
const filterSupplierId = ref('')
const filterWarehouseId = ref('')
const filterPoNo = ref('')

/** 把筛选控件状态并入查询参数（空值不传，由服务端按「不限」处理）。 */
function applyFilters(): void {
  query.status = filterStatus.value || undefined
  query.supplier_id = toPositiveInt(filterSupplierId.value)
  query.warehouse_id = toPositiveInt(filterWarehouseId.value)
  query.po_no = filterPoNo.value.trim() || undefined
}

async function fetchList(): Promise<void> {
  loading.value = true
  try {
    const result = await listPurchaseOrders({ ...query })
    list.value = result.list
    total.value = result.total
  } catch (error) {
    list.value = []
    total.value = 0
    ElMessage.error(describeError(error))
  } finally {
    loading.value = false
  }
}

function onSearch(): void {
  applyFilters()
  query.page = 1
  void fetchList()
}

function onReset(): void {
  filterStatus.value = ''
  filterSupplierId.value = ''
  filterWarehouseId.value = ''
  filterPoNo.value = ''
  applyFilters()
  query.page = 1
  void fetchList()
}

function onPageChange(page: number): void {
  query.page = page
  void fetchList()
}

function onSizeChange(pageSize: number): void {
  query.pageSize = pageSize
  query.page = 1
  void fetchList()
}

function openDetail(row: PurchaseOrderSummary): void {
  void router.push({
    name: 'purchasing-order-detail',
    params: { id: String(row.purchase_order_id) },
  })
}

onMounted(() => {
  void fetchList()
})
</script>

<style scoped>
.po-list__head {
  display: flex;
  align-items: baseline;
  gap: 12px;
}
.po-list__title {
  font-weight: 600;
}
.po-list__subtitle {
  color: #909399;
  font-size: 12px;
}
.po-list__filters {
  margin-bottom: 4px;
}
.po-list__pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
