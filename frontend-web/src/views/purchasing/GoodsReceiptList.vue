<template>
  <div class="gr-list" data-testid="gr-list">
    <el-card shadow="never">
      <template #header>
        <div class="gr-list__head">
          <span class="gr-list__title">到货单</span>
          <span class="gr-list__subtitle">共 {{ total }} 条</span>
          <span class="gr-list__spacer" />
          <el-button
            v-if="canOperate"
            type="primary"
            data-testid="gr-create-open"
            @click="createVisible = true"
          >
            ＋ 登记到货
          </el-button>
        </div>
      </template>

      <el-form :inline="true" class="gr-list__filters" @submit.prevent>
        <el-form-item label="状态">
          <el-select
            v-model="filterStatus"
            clearable
            placeholder="全部"
            style="width: 150px"
            data-testid="gr-filter-status"
            @change="onSearch"
          >
            <el-option
              v-for="status in GR_STATUSES"
              :key="status"
              :label="grStatusLabel(status)"
              :value="status"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="采购单 ID">
          <el-input
            v-model="filterPurchaseOrderId"
            placeholder="如 30001"
            style="width: 130px"
            data-testid="gr-filter-po"
            @keyup.enter="onSearch"
          />
        </el-form-item>
        <el-form-item label="仓库 ID">
          <el-input
            v-model="filterWarehouseId"
            placeholder="如 9004"
            style="width: 130px"
            data-testid="gr-filter-warehouse"
            @keyup.enter="onSearch"
          />
        </el-form-item>
        <el-form-item label="到货单号">
          <el-input
            v-model="filterReceiptNo"
            placeholder="GR 单号关键字"
            style="width: 180px"
            data-testid="gr-filter-no"
            @keyup.enter="onSearch"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" data-testid="gr-search" @click="onSearch">查询</el-button>
          <el-button data-testid="gr-reset" @click="onReset">重置</el-button>
        </el-form-item>
      </el-form>

      <div v-loading="loading">
        <el-table
          v-if="list.length > 0"
          :data="list"
          border
          stripe
          row-key="goods_receipt_id"
          data-testid="gr-table"
        >
          <el-table-column prop="receipt_no" label="到货单号" min-width="190" show-overflow-tooltip />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag :type="grStatusTagType(row.status)" size="small">
                {{ grStatusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="采购单 ID" width="110">
            <template #default="{ row }">{{ row.purchase_order_id }}</template>
          </el-table-column>
          <el-table-column label="仓库" width="100">
            <template #default="{ row }">{{ row.warehouse_id }}</template>
          </el-table-column>
          <el-table-column prop="receipt_date" label="到货日期" width="120" />
          <el-table-column label="到货总量" width="120" align="right">
            <template #default="{ row }">{{ row.total_qty }}</template>
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
                data-testid="gr-row-detail"
                @click="openDetail(row)"
              >
                详情
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-empty
          v-else-if="!loading"
          data-testid="gr-empty"
          description="暂无到货单"
          :image-size="90"
        />

        <el-pagination
          v-if="total > 0"
          class="gr-list__pager"
          background
          layout="total, sizes, prev, pager, next, jumper"
          :current-page="query.page"
          :page-size="query.pageSize"
          :page-sizes="[10, 20, 50, 100]"
          :total="total"
          data-testid="gr-pager"
          @current-change="onPageChange"
          @size-change="onSizeChange"
        />
      </div>
    </el-card>

    <!-- M8-R200：登记到货（草稿）；成功后刷新列表并跳到详情页确认到货 -->
    <GoodsReceiptCreateDialog v-model="createVisible" @created="onCreated" />
  </div>
</template>

<script setup lang="ts">
// 到货单列表页（M6-T11；`GET /purchasing/receipts`）。
//
// ★ 只读页：任何已登录成员可见；写操作（确认/作废）在详情页按角色显隐。
// ★ `total_qty` / `total_amount` 为后端字符串（scale 3/2），原样展示（口径铁律 ④）。
// ★ M8-R200：新增「登记到货」入口（`POST /purchasing/receipts`，OPERATOR+）——
//   此前只能调接口，界面无法建草稿，导致「确认到货」按钮无从触发。
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listGoodsReceipts } from '@/api/purchasing'
import type { GoodsReceiptQuery, GoodsReceiptStatus, GoodsReceiptSummary } from '@/types/purchasing'
import { useTeamStore } from '@/stores/team'
import { hasAnyRole } from '@/utils/permission'
import { describeError } from '@/utils/error'
import { formatDateTime } from '@/utils/format'
import { toPositiveInt } from '@/views/sales/salesQuery'
import GoodsReceiptCreateDialog from './GoodsReceiptCreateDialog.vue'
import { GR_STATUSES, grStatusLabel, grStatusTagType } from './purchasingStatus'

/** 到货写动作角色（与后端 `@RequireRole(OWNER,ADMIN,OPERATOR,APPROVER)` 对齐；体验层）。 */
const OPERATE_ROLES = ['OWNER', 'ADMIN', 'OPERATOR', 'APPROVER']

const router = useRouter()
const teamStore = useTeamStore()

const canOperate = computed(() => hasAnyRole(teamStore.role, OPERATE_ROLES))
const createVisible = ref(false)

const list = ref<GoodsReceiptSummary[]>([])
const total = ref(0)
const loading = ref(false)

const query = reactive<GoodsReceiptQuery>({ page: 1, pageSize: 20 })
const filterStatus = ref<GoodsReceiptStatus | ''>('')
const filterPurchaseOrderId = ref('')
const filterWarehouseId = ref('')
const filterReceiptNo = ref('')

function applyFilters(): void {
  query.status = filterStatus.value || undefined
  query.purchase_order_id = toPositiveInt(filterPurchaseOrderId.value)
  query.warehouse_id = toPositiveInt(filterWarehouseId.value)
  query.receipt_no = filterReceiptNo.value.trim() || undefined
}

async function fetchList(): Promise<void> {
  loading.value = true
  try {
    const result = await listGoodsReceipts({ ...query })
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
  filterPurchaseOrderId.value = ''
  filterWarehouseId.value = ''
  filterReceiptNo.value = ''
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

function openDetail(row: GoodsReceiptSummary): void {
  void router.push({
    name: 'purchasing-receipt-detail',
    params: { id: String(row.goods_receipt_id) },
  })
}

/**
 * 登记成功回调：刷新列表 → 跳到新到货单详情（用户下一步就是「确认到货」）。
 *
 * ★ `replayed=true` 说明是幂等重放（该次登记此前已成功），不要提示「新建成功」以免误导。
 */
function onCreated(goodsReceiptId: number, receiptNo: string, replayed: boolean): void {
  if (replayed) {
    ElMessage.warning(`该登记此前已提交过，服务端返回首次结果：${receiptNo}（未重复创建）`)
  } else {
    ElMessage.success(`已登记到货单 ${receiptNo}（草稿）。下一步：在详情页「确认到货」才会入库。`)
  }
  void fetchList()
  void router.push({
    name: 'purchasing-receipt-detail',
    params: { id: String(goodsReceiptId) },
  })
}

onMounted(() => {
  void fetchList()
})
</script>

<style scoped>
.gr-list__head {
  display: flex;
  align-items: center;
  gap: 12px;
}
.gr-list__title {
  font-weight: 600;
}
.gr-list__subtitle {
  color: #909399;
  font-size: 12px;
}
.gr-list__spacer {
  flex: 1;
}
.gr-list__filters {
  margin-bottom: 4px;
}
.gr-list__pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
