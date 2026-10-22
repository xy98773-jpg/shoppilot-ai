<template>
  <div class="txn-page">
    <el-card shadow="never">
      <template #header>
        <div class="txn-page__head">
          <span class="txn-page__title">库存流水</span>
          <span class="txn-page__subtitle">不可变账本（只读；冲销另记一条反向流水）</span>
        </div>
      </template>

      <el-form :inline="true" class="txn-page__filters">
        <el-form-item label="仓库 ID">
          <el-input v-model="warehouseId" placeholder="如 9004" style="width: 120px" />
        </el-form-item>
        <el-form-item label="SKU ID">
          <el-input v-model="skuId" placeholder="如 9002" style="width: 120px" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="txnType" clearable placeholder="全部" style="width: 210px">
            <el-option v-for="t in TXN_TYPES" :key="t" :label="txnTypeLabel(t)" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="起始日">
          <el-date-picker
            v-model="dateFrom"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="yyyy-MM-dd"
            style="width: 155px"
          />
        </el-form-item>
        <el-form-item label="截止日">
          <el-date-picker
            v-model="dateTo"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="yyyy-MM-dd"
            style="width: 155px"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="onSearch">查询</el-button>
          <el-button @click="onReset">重置</el-button>
        </el-form-item>
      </el-form>

      <PageTable
        :columns="columns"
        :rows="list"
        :loading="loading"
        :total="total"
        :page="query.page"
        :page-size="query.pageSize"
        :actions-width="150"
        @page-change="changePage"
        @size-change="changeSize"
      >
        <template #occurredAt="{ row }">{{ formatDateTime(row.occurred_at) }}</template>
        <template #txnType="{ row }">{{ txnTypeLabel(row.txn_type) }}</template>
        <template #bizType="{ row }">{{ bizTypeLabel(row.biz_type) }}</template>
        <template #actions="{ row }">
          <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          <!-- ★ 冲销仅 OWNER/ADMIN 可见（体验层；服务端仍是唯一安全边界，角色不足 → TEAM-2003） -->
          <el-button
            v-if="canReverse"
            link
            type="warning"
            :disabled="row.txn_type === 'REVERSAL'"
            @click="openReverse(row)"
          >
            冲销
          </el-button>
        </template>
      </PageTable>
    </el-card>

    <el-dialog v-model="detailVisible" title="流水详情" width="720px">
      <el-descriptions v-if="detail" :column="2" border size="small">
        <el-descriptions-item label="流水 ID">{{ detail.id }}</el-descriptions-item>
        <el-descriptions-item label="类型">{{ txnTypeLabel(detail.txn_type) }}</el-descriptions-item>
        <el-descriptions-item label="业务类型">{{ bizTypeLabel(detail.biz_type) }}</el-descriptions-item>
        <el-descriptions-item label="仓库 / SKU">
          {{ detail.warehouse_id }} / {{ detail.sku_code || detail.sku_id }}
        </el-descriptions-item>
        <el-descriptions-item label="变更量">{{ detail.qty_delta }}</el-descriptions-item>
        <el-descriptions-item label="变更前 / 后">
          {{ detail.qty_before }} → {{ detail.qty_after }}
        </el-descriptions-item>
        <el-descriptions-item label="业务引用">{{ detail.biz_ref_no || detail.biz_ref_id || '-' }}</el-descriptions-item>
        <el-descriptions-item label="幂等键">{{ detail.idempotency_key }}</el-descriptions-item>
        <el-descriptions-item label="原因">{{ detail.reason || '-' }}</el-descriptions-item>
        <el-descriptions-item label="被冲销流水">{{ detail.reversed_txn_id ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="发生时间">{{ formatDateTime(detail.occurred_at) }}</el-descriptions-item>
        <el-descriptions-item label="操作人">{{ detail.operator_id ?? '-' }}</el-descriptions-item>
      </el-descriptions>
    </el-dialog>

    <!-- ★ 冲销弹窗也只在有权限时挂载：无权角色连「冲销」字样都不该出现在 DOM 里 -->
    <el-dialog v-if="canReverse" v-model="reverseVisible" title="冲销流水" width="520px">
      <el-form label-width="90px">
        <el-form-item label="流水 ID">
          <span>{{ reverseTarget?.id }}</span>
        </el-form-item>
        <el-form-item label="冲销原因" required>
          <el-input v-model="reverseReason" type="textarea" :rows="3" maxlength="200" show-word-limit />
        </el-form-item>
        <p class="txn-page__note">
          冲销会新增一条反向流水，原流水保持不变；同一笔流水只能冲销一次（服务端 INV-5009）。
        </p>
      </el-form>
      <template #footer>
        <el-button @click="reverseVisible = false">取消</el-button>
        <el-button type="primary" :loading="reversing" @click="onReverse">确认冲销</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
// 库存流水列表（F-INV-02）+ 详情 + 冲销（F-INV-06）。
//
// ★ 角色显隐仅是体验层：冲销端点静态要求 OWNER/ADMIN（角色不足 → TEAM-2003），服务端才是安全边界。
// ★ 冲销必须带 `Idempotency-Key`：一次意图一个键，失败重试复用；命中重放时提示「已提交过」。
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageTable from '@/components/common/PageTable.vue'
import { getTransaction, listTransactions, reverseTransaction } from '@/api/inventory'
import type { TransactionQuery, TransactionView } from '@/types/inventory'
import type { TableColumn } from '@/types/ui'
import { usePagination } from '@/composables/usePagination'
import { useTeamStore } from '@/stores/team'
import { hasAnyRole } from '@/utils/permission'
import { describeError } from '@/utils/error'
import { formatDateTime } from '@/utils/format'
import { createIdempotencyIntent } from '@/utils/idempotency'
import { bizTypeLabel, txnTypeLabel } from '@/utils/terms'
import { toPositiveInt } from '@/views/sales/salesQuery'

const TXN_TYPES = [
  'INIT_IMPORT',
  'PURCHASE_INBOUND',
  'SALE_OUTBOUND',
  'COUNT_ADJUST',
  'MANUAL_ADJUST',
  'REVERSAL',
]

const teamStore = useTeamStore()
/** 冲销按钮显隐（体验层）：与后端 `@RequireRole(OWNER,ADMIN)` 对齐。 */
const canReverse = computed(() => hasAnyRole(teamStore.role, ['OWNER', 'ADMIN']))

const warehouseId = ref('')
const skuId = ref('')
const txnType = ref('')
const dateFrom = ref('')
const dateTo = ref('')

const columns: TableColumn[] = [
  { prop: 'id', label: '流水 ID', width: 100 },
  { prop: 'warehouse_id', label: '仓库 ID', width: 100 },
  { prop: 'sku_code', label: 'SKU（商品规格）', minWidth: 130 },
  { prop: 'txn_type', label: '类型', width: 190, slot: 'txnType' },
  { prop: 'biz_type', label: '业务类型', width: 150, slot: 'bizType' },
  { prop: 'qty_delta', label: '变更量', width: 110 },
  { prop: 'qty_after', label: '变更后', width: 110 },
  { prop: 'reason', label: '原因', minWidth: 160 },
  { prop: 'occurred_at', label: '发生时间', minWidth: 170, slot: 'occurredAt' },
]

const { list, total, loading, query, load, changePage, changeSize } = usePagination<TransactionView>(
  (q) => listTransactions(q as TransactionQuery),
)

function applyFilters(): void {
  query.warehouse_id = toPositiveInt(warehouseId.value)
  query.sku_id = toPositiveInt(skuId.value)
  query.txn_type = txnType.value || undefined
  query.dateFrom = dateFrom.value || undefined
  query.dateTo = dateTo.value || undefined
}

function onSearch(): void {
  applyFilters()
  query.page = 1
  void load()
}

function onReset(): void {
  warehouseId.value = ''
  skuId.value = ''
  txnType.value = ''
  dateFrom.value = ''
  dateTo.value = ''
  query.warehouse_id = undefined
  query.sku_id = undefined
  query.txn_type = undefined
  query.dateFrom = undefined
  query.dateTo = undefined
  query.page = 1
  void load()
}

// ---------------------------------------------------------------- 详情
const detailVisible = ref(false)
const detail = ref<TransactionView | null>(null)

async function openDetail(row: TransactionView): Promise<void> {
  try {
    detail.value = await getTransaction(row.id)
    detailVisible.value = true
  } catch (error) {
    ElMessage.error(describeError(error))
  }
}

// ---------------------------------------------------------------- 冲销
const reverseVisible = ref(false)
const reverseTarget = ref<TransactionView | null>(null)
const reverseReason = ref('')
const reversing = ref(false)
const reverseIntent = createIdempotencyIntent()

function openReverse(row: TransactionView): void {
  reverseTarget.value = row
  reverseReason.value = ''
  reverseIntent.reset()
  reverseVisible.value = true
}

async function onReverse(): Promise<void> {
  if (!reverseTarget.value) {
    return
  }
  if (!reverseReason.value.trim()) {
    ElMessage.warning('请填写冲销原因')
    return
  }
  reversing.value = true
  try {
    const result = await reverseTransaction(
      reverseTarget.value.id,
      reverseReason.value.trim(),
      reverseIntent.key(),
    )
    if (result.replayed) {
      ElMessage.warning('该操作已提交过，服务端返回首次结果，未重复冲销')
    } else {
      ElMessage.success(`冲销成功，新流水 ${result.data.transaction_id ?? '-'}`)
      reverseIntent.reset()
    }
    reverseVisible.value = false
    await load()
  } catch (error) {
    // 失败不重置键：重试必须复用同一 Idempotency-Key
    ElMessage.error(describeError(error))
  } finally {
    reversing.value = false
  }
}

onMounted(() => {
  void load()
})
</script>

<style scoped>
.txn-page__head {
  display: flex;
  align-items: baseline;
  gap: 12px;
}
.txn-page__title {
  font-weight: 600;
}
.txn-page__subtitle {
  color: #909399;
  font-size: 12px;
}
.txn-page__filters {
  margin-bottom: 4px;
}
.txn-page__note {
  margin: 0;
  padding-left: 90px;
  color: #909399;
  font-size: 12px;
  line-height: 1.6;
}
</style>
