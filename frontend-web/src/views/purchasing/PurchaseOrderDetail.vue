<template>
  <div class="po-detail" data-testid="po-detail">
    <el-card v-loading="loading" shadow="never">
      <template #header>
        <div class="po-detail__head">
          <span class="po-detail__title">采购单 {{ order?.po_no || orderId }}</span>
          <el-tag v-if="order" :type="poStatusTagType(order.status)" size="small">
            {{ poStatusLabel(order.status) }}
          </el-tag>
          <span class="po-detail__spacer" />
          <el-button size="small" data-testid="po-back" @click="goList">返回列表</el-button>
          <el-button
            v-if="canConfirm"
            size="small"
            type="primary"
            :loading="acting"
            data-testid="po-action-confirm"
            @click="onConfirm"
          >
            确认采购单
          </el-button>
          <el-button
            v-if="canOrder"
            size="small"
            type="primary"
            :loading="acting"
            data-testid="po-action-order"
            @click="onOrder"
          >
            下单
          </el-button>
          <el-button
            v-if="canCancel"
            size="small"
            type="danger"
            plain
            :loading="acting"
            data-testid="po-action-cancel"
            @click="onCancel"
          >
            取消采购单
          </el-button>
        </div>
      </template>

      <el-alert
        v-if="errorText"
        type="error"
        :closable="false"
        show-icon
        :title="errorText"
        class="po-detail__alert"
      />

      <template v-if="order">
        <el-descriptions :column="3" border size="small" data-testid="po-detail-desc">
          <el-descriptions-item label="采购单号">{{ order.po_no }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            {{ poStatusLabel(order.status) }}
          </el-descriptions-item>
          <el-descriptions-item label="来源">{{ order.source }}</el-descriptions-item>
          <el-descriptions-item label="供应商 ID">{{ order.supplier_id ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="仓库 ID">{{ order.warehouse_id }}</el-descriptions-item>
          <el-descriptions-item label="预计到货">
            {{ order.expected_arrival_date || '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="订购总量">{{ order.total_qty }}</el-descriptions-item>
          <el-descriptions-item label="已收数量">{{ order.received_qty }}</el-descriptions-item>
          <el-descriptions-item label="总金额">{{ order.total_amount }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">
            {{ formatDateTime(order.created_at) }}
          </el-descriptions-item>
          <el-descriptions-item label="确认时间">
            {{ formatDateTime(order.confirmed_at || '') }}
          </el-descriptions-item>
          <el-descriptions-item label="下单时间">
            {{ formatDateTime(order.ordered_at || '') }}
          </el-descriptions-item>
          <el-descriptions-item label="取消时间">
            {{ formatDateTime(order.cancelled_at || '') }}
          </el-descriptions-item>
          <el-descriptions-item label="取消原因">{{ order.cancel_reason || '-' }}</el-descriptions-item>
          <el-descriptions-item label="版本">{{ order.version }}</el-descriptions-item>
          <el-descriptions-item label="备注" :span="3">{{ order.remark || '-' }}</el-descriptions-item>
        </el-descriptions>

        <p class="po-detail__label">采购明细</p>
        <el-table
          v-if="order.items.length > 0"
          :data="order.items"
          border
          stripe
          row-key="id"
          data-testid="po-detail-items"
        >
          <el-table-column prop="line_no" label="行号" width="80" />
          <el-table-column prop="sku_id" label="SKU" width="120" />
          <el-table-column label="订购量" width="120" align="right">
            <template #default="{ row }">{{ row.ordered_qty }}</template>
          </el-table-column>
          <el-table-column label="已收量" width="120" align="right">
            <template #default="{ row }">{{ row.received_qty }}</template>
          </el-table-column>
          <el-table-column label="单价" width="120" align="right">
            <template #default="{ row }">{{ row.unit_price }}</template>
          </el-table-column>
          <el-table-column label="金额" width="130" align="right">
            <template #default="{ row }">{{ row.amount }}</template>
          </el-table-column>
          <el-table-column label="预计到货" min-width="130">
            <template #default="{ row }">{{ row.expected_arrival_date || '-' }}</template>
          </el-table-column>
        </el-table>
        <el-empty v-else description="暂无采购明细" :image-size="70" data-testid="po-detail-items-empty" />
      </template>
      <el-empty v-else-if="!loading" description="暂无采购单" :image-size="80" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
// 采购单详情页（M6-T11；`GET /purchasing/orders/{id}` + A1/A2/A3 动作）。
//
// ★ `expected_version` **必须**取自当前详情响应的 `version`（乐观锁），绝不硬编码；
//   缺失时前端直接拦下，不发请求。
// ★ 取消原因必填（服务端 `COM-0001`）：输入为空/空白只在本地提示，不打扰服务端。
// ★ 动作成功后重新拉详情（不本地改状态充数），服务端是唯一数据源。
// ★ 数量/金额原样展示后端字符串（scale 3/4/2），不做精度换算（口径铁律 ④）。
// ★ 角色显隐仅为体验层：服务端 `@RequireRole(OWNER/ADMIN/APPROVER)` 才是安全边界。
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { cancelPurchaseOrder, confirmPurchaseOrder, getPurchaseOrder, orderPurchaseOrder } from '@/api/purchasing'
import type { PurchaseOrderDetail } from '@/types/purchasing'
import { useTeamStore } from '@/stores/team'
import { hasAnyRole } from '@/utils/permission'
import { describeError } from '@/utils/error'
import { formatDateTime } from '@/utils/format'
import { poStatusLabel, poStatusTagType } from './purchasingStatus'

/** 采购单动作角色（与后端 `@RequireRole(OWNER,ADMIN,APPROVER)` 对齐）。 */
const ACTION_ROLES = ['OWNER', 'ADMIN', 'APPROVER']

const route = useRoute()
const router = useRouter()
const teamStore = useTeamStore()

const orderId = computed(() => String(route.params.id ?? ''))
const order = ref<PurchaseOrderDetail | null>(null)
const loading = ref(false)
const acting = ref(false)
const errorText = ref('')

const canApprove = computed(() => hasAnyRole(teamStore.role, ACTION_ROLES))
const canConfirm = computed(() => canApprove.value && order.value?.status === 'DRAFT')
const canOrder = computed(() => canApprove.value && order.value?.status === 'PENDING')
const canCancel = computed(
  () =>
    canApprove.value &&
    ['DRAFT', 'PENDING', 'ORDERED', 'PARTIALLY_RECEIVED'].includes(order.value?.status ?? ''),
)

async function fetchDetail(): Promise<void> {
  loading.value = true
  try {
    order.value = await getPurchaseOrder(orderId.value)
    errorText.value = ''
  } catch (error) {
    order.value = null
    errorText.value = describeError(error)
    ElMessage.error(errorText.value)
  } finally {
    loading.value = false
  }
}

/** 取详情里的乐观锁版本号；缺失则提示并放弃本次提交。 */
function expectedVersion(): number | null {
  const version = order.value?.version
  if (typeof version !== 'number') {
    ElMessage.error('采购单版本号缺失，请刷新页面后重试')
    return null
  }
  return version
}

async function runAction(action: 'confirm' | 'order'): Promise<void> {
  const version = expectedVersion()
  if (version === null) {
    return
  }
  acting.value = true
  try {
    if (action === 'confirm') {
      await confirmPurchaseOrder(orderId.value, version)
      ElMessage.success('采购单已确认')
    } else {
      await orderPurchaseOrder(orderId.value, version)
      ElMessage.success('采购单已下单')
    }
    await fetchDetail()
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    acting.value = false
  }
}

function onConfirm(): void {
  void runAction('confirm')
}

function onOrder(): void {
  void runAction('order')
}

async function onCancel(): Promise<void> {
  let value = ''
  try {
    const result = await ElMessageBox.prompt('取消原因将记入采购单（必填，≤255 字）', '取消采购单', {
      confirmButtonText: '确认取消',
      cancelButtonText: '返回',
      inputType: 'textarea',
      inputPlaceholder: '如：供应商缺货',
    })
    value = String(result.value ?? '')
  } catch {
    return
  }
  const reason = value.trim()
  if (!reason) {
    // 前端拦下：不发请求（服务端同样会以 COM-0001 拒绝）
    ElMessage.warning('请填写取消原因')
    return
  }
  acting.value = true
  try {
    await cancelPurchaseOrder(orderId.value, reason)
    ElMessage.success('采购单已取消')
    await fetchDetail()
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    acting.value = false
  }
}

function goList(): void {
  void router.push({ name: 'purchasing-orders' })
}

onMounted(() => {
  void fetchDetail()
})
</script>

<style scoped>
.po-detail__head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.po-detail__title {
  font-weight: 600;
}
.po-detail__spacer {
  flex: 1;
}
.po-detail__alert {
  margin-bottom: 12px;
}
.po-detail__label {
  margin: 16px 0 6px;
  font-weight: 600;
  font-size: 13px;
}
</style>
