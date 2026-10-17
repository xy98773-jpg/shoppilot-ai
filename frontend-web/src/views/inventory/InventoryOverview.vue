<template>
  <div class="overview-page">
    <el-card shadow="never">
      <template #header>
        <div class="overview-page__head">
          <span class="overview-page__title">库存概览</span>
          <span class="overview-page__subtitle">当前量与预警（只读）</span>
        </div>
      </template>

      <!-- ★ 口径说明（验收点：必须存在，且写明 available_qty 不是补货口径 IP） -->
      <el-alert
        class="overview-page__caliber"
        type="info"
        :closable="false"
        show-icon
        title="口径说明：available_qty = on_hand_qty − committed_qty，不含在途，不是补货口径 IP"
        description="本页四列分列展示，不做任何合并或推算。in_transit_qty 是原始在途量（≠ effective_in_transit），前端不得用 on_hand + in_transit − committed 自算 IP —— 补货口径 IP 属 M4（R67 / R77）。"
      />

      <el-form :inline="true" class="overview-page__filters">
        <el-form-item label="仓库 ID">
          <el-input v-model="warehouseId" placeholder="如 9004" style="width: 130px" />
        </el-form-item>
        <el-form-item label="SKU ID">
          <el-input v-model="skuId" placeholder="如 9002" style="width: 130px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="onSearch">查询</el-button>
          <el-button @click="onReset">重置</el-button>
        </el-form-item>
      </el-form>

      <PageTable
        :columns="balanceColumns"
        :rows="list"
        :loading="loading"
        :total="total"
        :page="query.page"
        :page-size="query.pageSize"
        row-key="id"
        @page-change="changePage"
        @size-change="changeSize"
      >
        <template #onHand="{ row }">{{ row.on_hand_qty }}</template>
        <template #inTransit="{ row }">{{ row.in_transit_qty }}</template>
        <template #committed="{ row }">{{ row.committed_qty }}</template>
        <template #available="{ row }">{{ row.available_qty }}</template>
        <template #lastTxnAt="{ row }">{{ formatDateTime(row.last_txn_at) }}</template>
      </PageTable>
    </el-card>

    <el-card shadow="never" class="overview-page__alerts">
      <template #header>
        <div class="overview-page__head">
          <span class="overview-page__title">低库存 / 缺货预警</span>
          <span class="overview-page__subtitle">
            来源 `GET /inventory/alerts`（无状态、不落库；阈值为服务端策略值）
          </span>
        </div>
      </template>

      <el-table :data="alerts" border size="small" stripe>
        <el-table-column prop="alert_type" label="类型" width="130" />
        <el-table-column prop="warehouse_id" label="仓库 ID" width="100" />
        <el-table-column prop="sku_id" label="SKU ID" width="100" />
        <el-table-column prop="sku_code" label="SKU 编码" min-width="140" />
        <el-table-column prop="on_hand_qty" label="现有量" width="110" />
        <el-table-column prop="available_qty" label="可用量（不含在途）" width="170" />
        <el-table-column prop="threshold" label="阈值" width="110" />
        <el-table-column prop="message" label="提示" min-width="220" />
        <template #empty>
          <el-empty description="暂无预警" :image-size="70" />
        </template>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
// 库存概览（F-INV-01/05）：余额四列分列 + 低库存预警。
//
// ★★ 口径铁律 ②（R67 / R77）：
//   - 本页**只**原样展示后端返回的四个数量列，不做任何合并/相加；
//   - `available_qty` 是生成列（on_hand − committed，不含在途），**不是**补货口径 IP；
//   - M3 阶段没有 `effective_in_transit` 数据源（`InventoryInTransitQueryService` 仍是骨架），
//     故**禁止**展示伪 IP 列，也禁止用 on_hand + in_transit − committed 自算。
// ★ 数量是字符串（"10.000"），原样展示，不转 Number、不做精确算术（口径铁律 ④）。
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageTable from '@/components/common/PageTable.vue'
import { listAlerts, listBalances } from '@/api/inventory'
import type { AlertView, BalanceView, BalanceQuery } from '@/types/inventory'
import type { TableColumn } from '@/types/ui'
import { usePagination } from '@/composables/usePagination'
import { describeError } from '@/utils/error'
import { formatDateTime } from '@/utils/format'
import { toPositiveInt } from '@/views/sales/salesQuery'

const warehouseId = ref('')
const skuId = ref('')

/** 四列分列：on_hand / in_transit / committed / available（顺序与口径文档一致）。 */
const balanceColumns: TableColumn[] = [
  { prop: 'warehouse_id', label: '仓库 ID', width: 100 },
  { prop: 'sku_id', label: 'SKU ID', width: 100 },
  { prop: 'sku_code', label: 'SKU 编码', minWidth: 130 },
  { prop: 'on_hand_qty', label: '现有库存 on_hand', width: 150, slot: 'onHand' },
  { prop: 'in_transit_qty', label: '原始在途 in_transit（非 IP）', width: 200, slot: 'inTransit' },
  { prop: 'committed_qty', label: '已承诺 committed', width: 150, slot: 'committed' },
  { prop: 'available_qty', label: '可用量 available（不含在途）', width: 210, slot: 'available' },
  { prop: 'last_txn_at', label: '最近变动', minWidth: 170, slot: 'lastTxnAt' },
]

const { list, total, loading, query, load, changePage, changeSize } = usePagination<BalanceView>((q) =>
  listBalances(q as BalanceQuery),
)

const alerts = ref<AlertView[]>([])

function applyFilters(): void {
  query.warehouse_id = toPositiveInt(warehouseId.value)
  query.sku_id = toPositiveInt(skuId.value)
}

async function loadAlerts(): Promise<void> {
  try {
    const result = await listAlerts({
      page: 1,
      pageSize: 20,
      warehouse_id: toPositiveInt(warehouseId.value),
      sku_id: toPositiveInt(skuId.value),
    })
    alerts.value = result.list
  } catch (error) {
    ElMessage.error(describeError(error))
  }
}

function onSearch(): void {
  applyFilters()
  query.page = 1
  void load()
  void loadAlerts()
}

function onReset(): void {
  warehouseId.value = ''
  skuId.value = ''
  query.warehouse_id = undefined
  query.sku_id = undefined
  query.page = 1
  void load()
  void loadAlerts()
}

onMounted(() => {
  void load()
  void loadAlerts()
})
</script>

<style scoped>
.overview-page__head {
  display: flex;
  align-items: baseline;
  gap: 12px;
}
.overview-page__title {
  font-weight: 600;
}
.overview-page__subtitle {
  color: #909399;
  font-size: 12px;
}
.overview-page__caliber {
  margin-bottom: 12px;
}
.overview-page__filters {
  margin-bottom: 4px;
}
.overview-page__alerts {
  margin-top: 16px;
}
</style>
