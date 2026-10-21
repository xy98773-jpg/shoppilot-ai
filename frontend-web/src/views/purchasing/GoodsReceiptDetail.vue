<template>
  <div class="gr-detail" data-testid="gr-detail">
    <el-card v-loading="loading" shadow="never">
      <template #header>
        <div class="gr-detail__head">
          <span class="gr-detail__title">到货单 {{ receipt?.receipt_no || receiptId }}</span>
          <el-tag v-if="receipt" :type="grStatusTagType(receipt.status)" size="small">
            {{ grStatusLabel(receipt.status) }}
          </el-tag>
          <span class="gr-detail__spacer" />
          <el-button size="small" data-testid="gr-back" @click="goList">返回列表</el-button>
          <el-button
            v-if="canConfirm"
            size="small"
            type="primary"
            :loading="confirming"
            data-testid="gr-action-confirm"
            @click="onConfirm"
          >
            确认到货
          </el-button>
          <el-button
            v-if="canCancel"
            size="small"
            type="danger"
            plain
            :loading="cancelling"
            data-testid="gr-action-cancel"
            @click="onCancel"
          >
            作废到货单
          </el-button>
        </div>
      </template>

      <el-alert
        v-if="errorText"
        type="error"
        :closable="false"
        show-icon
        :title="errorText"
        class="gr-detail__alert"
      />

      <template v-if="receipt">
        <el-descriptions :column="3" border size="small" data-testid="gr-detail-desc">
          <el-descriptions-item label="到货单号">{{ receipt.receipt_no }}</el-descriptions-item>
          <el-descriptions-item label="关联采购单">
            {{ receipt.purchase_order_id }}
          </el-descriptions-item>
          <el-descriptions-item label="仓库 ID">{{ receipt.warehouse_id }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            {{ grStatusLabel(receipt.status) }}
          </el-descriptions-item>
          <el-descriptions-item label="到货日期">{{ receipt.receipt_date }}</el-descriptions-item>
          <el-descriptions-item label="到货总量">{{ receipt.total_qty }}</el-descriptions-item>
          <el-descriptions-item label="总金额">{{ receipt.total_amount }}</el-descriptions-item>
          <el-descriptions-item label="确认时间">
            {{ formatDateTime(receipt.confirmed_at || '') }}
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">
            {{ formatDateTime(receipt.created_at) }}
          </el-descriptions-item>
          <el-descriptions-item label="备注" :span="3">{{ receipt.remark || '-' }}</el-descriptions-item>
        </el-descriptions>

        <p class="gr-detail__label">到货明细</p>
        <el-table
          v-if="receipt.items.length > 0"
          :data="receipt.items"
          border
          stripe
          row-key="goods_receipt_item_id"
          data-testid="gr-detail-items"
        >
          <el-table-column prop="purchase_order_item_id" label="采购明细 ID" width="120" />
          <el-table-column prop="sku_id" label="SKU" width="120" />
          <el-table-column label="到货数量" width="120" align="right">
            <template #default="{ row }">{{ row.received_qty }}</template>
          </el-table-column>
          <el-table-column label="单价" width="120" align="right">
            <template #default="{ row }">{{ row.unit_price }}</template>
          </el-table-column>
          <el-table-column label="金额" width="130" align="right">
            <template #default="{ row }">{{ row.amount }}</template>
          </el-table-column>
          <el-table-column label="批号" min-width="130">
            <template #default="{ row }">{{ row.batch_no || '-' }}</template>
          </el-table-column>
          <el-table-column label="备注" min-width="140">
            <template #default="{ row }">{{ row.remark || '-' }}</template>
          </el-table-column>
        </el-table>
        <el-empty v-else description="暂无到货明细" :image-size="70" data-testid="gr-detail-items-empty" />
      </template>
      <el-empty v-else-if="!loading" description="暂无到货单" :image-size="80" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
// 到货单详情页（M6-T11；`GET /purchasing/receipts/{id}` + 确认到货）。
//
// ★ 确认到货**必须**带 `Idempotency-Key`（服务端 `goods_receipts.idempotency_key NOT NULL`，
//   缺失 ⇒ COM-0010），走 `postWithIdempotency`；键在一次点击内固定复用。
// ★ 确认成功后**重新拉详情**，不本地改状态充数（服务端会重算采购单到货进度）。
// ★ `PO-9002`（超额）优先展示 `details[0].message`（含「待收数量为 X，本次登记 Y」）。
// ★ 角色显隐仅为体验层：服务端 `@RequireRole(OPERATOR+)` 才是安全边界。
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { cancelGoodsReceipt, confirmGoodsReceipt, getGoodsReceipt } from '@/api/purchasing'
import type { GoodsReceiptDetail } from '@/types/purchasing'
import { useTeamStore } from '@/stores/team'
import { hasAnyRole } from '@/utils/permission'
import { describeError } from '@/utils/error'
import { formatDateTime } from '@/utils/format'
import { grStatusLabel, grStatusTagType } from './purchasingStatus'

/** 到货写动作角色（与后端 `@RequireRole(OWNER,ADMIN,OPERATOR,APPROVER)` 对齐）。 */
const OPERATE_ROLES = ['OWNER', 'ADMIN', 'OPERATOR', 'APPROVER']

const route = useRoute()
const router = useRouter()
const teamStore = useTeamStore()

const receiptId = computed(() => String(route.params.id ?? ''))
const receipt = ref<GoodsReceiptDetail | null>(null)
const loading = ref(false)
const confirming = ref(false)
const cancelling = ref(false)
const errorText = ref('')

const canOperate = computed(() => hasAnyRole(teamStore.role, OPERATE_ROLES))
const canConfirm = computed(() => canOperate.value && receipt.value?.status === 'DRAFT')
const canCancel = computed(() => canOperate.value && receipt.value?.status === 'DRAFT')

async function fetchDetail(): Promise<void> {
  loading.value = true
  try {
    receipt.value = await getGoodsReceipt(receiptId.value)
    errorText.value = ''
  } catch (error) {
    receipt.value = null
    errorText.value = describeError(error)
    ElMessage.error(errorText.value)
  } finally {
    loading.value = false
  }
}

/**
 * 错误文案：`PO-9002`（超额）优先取字段级明细，其余走统一 `describeError`。
 */
function receiptErrorText(error: unknown): string {
  const err = error as { code?: string; details?: { message?: string }[] }
  if (err?.code === 'PO-9002' && err.details && err.details.length > 0) {
    const detailMessage = err.details[0]?.message
    if (detailMessage) {
      return detailMessage
    }
  }
  return describeError(error)
}

async function onConfirm(): Promise<void> {
  if (!receipt.value) {
    return
  }
  const target = receipt.value
  try {
    await ElMessageBox.confirm(
      '确认后将产生 PURCHASE_INBOUND 库存流水，且本到货单不可再编辑，确定吗？',
      '确认到货',
      { type: 'warning', confirmButtonText: '确认入库', cancelButtonText: '再检查一下' },
    )
  } catch {
    return
  }
  // 一次点击一个键：本次提交（含重试）复用同一个 Idempotency-Key
  const idempotencyKey = `FE-GR-CONFIRM-${target.goods_receipt_id}-${Date.now()}`
  confirming.value = true
  try {
    const result = await confirmGoodsReceipt(target.goods_receipt_id, idempotencyKey)
    if (result.replayed) {
      ElMessage.warning('该确认已提交过，服务端返回首次结果，未重复入库')
    } else {
      ElMessage.success('到货已确认，库存已入库')
    }
    await fetchDetail()
  } catch (error) {
    ElMessage.error(receiptErrorText(error))
  } finally {
    confirming.value = false
  }
}

async function onCancel(): Promise<void> {
  if (!receipt.value) {
    return
  }
  const target = receipt.value
  try {
    await ElMessageBox.confirm('作废后该草稿不可再用，确定作废吗？', '作废到货单', {
      type: 'warning',
      confirmButtonText: '确认作废',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  cancelling.value = true
  try {
    await cancelGoodsReceipt(target.goods_receipt_id)
    ElMessage.success('到货单已作废')
    await fetchDetail()
  } catch (error) {
    ElMessage.error(receiptErrorText(error))
  } finally {
    cancelling.value = false
  }
}

function goList(): void {
  void router.push({ name: 'purchasing-receipts' })
}

onMounted(() => {
  void fetchDetail()
})
</script>

<style scoped>
.gr-detail__head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.gr-detail__title {
  font-weight: 600;
}
.gr-detail__spacer {
  flex: 1;
}
.gr-detail__alert {
  margin-bottom: 12px;
}
.gr-detail__label {
  margin: 16px 0 6px;
  font-weight: 600;
  font-size: 13px;
}
</style>
