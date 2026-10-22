<template>
  <el-dialog
    v-model="visible"
    title="登记到货（生成草稿，不写库存）"
    width="1020px"
    destroy-on-close
    data-testid="gr-create-dialog"
  >
    <el-alert type="warning" :closable="false" show-icon class="gr-create__tip">
      <template #title>
        登记只生成 <b>草稿（DRAFT）</b>，<b>不动库存</b>；必须再到到货单详情页点「确认到货」，
        才会产生 <code>PURCHASE_INBOUND</code>（采购入库）流水。只有
        <b>采购中（ORDERED）/ 部分到货（PARTIALLY_RECEIVED）</b> 的采购单可以登记到货。
      </template>
    </el-alert>

    <el-form label-width="170px" class="gr-create__form">
      <el-form-item label="采购单 ID（必填）">
        <el-input
          v-model="poIdInput"
          placeholder="如 30001"
          style="width: 200px"
          data-testid="gr-create-po-input"
          @keyup.enter="loadPurchaseOrder"
        />
        <el-button
          class="gr-create__load"
          type="primary"
          plain
          :loading="poLoading"
          data-testid="gr-create-po-load"
          @click="loadPurchaseOrder"
        >
          加载采购单
        </el-button>
        <span class="gr-create__hint">
          ★ 数量 / 单价 / SKU 全部取自采购单，<b>供应商与仓库不可在此更改</b>
        </span>
      </el-form-item>

      <el-form-item v-if="poError" label=" ">
        <el-alert type="error" :closable="false" show-icon :title="poError" />
      </el-form-item>

      <template v-if="po">
        <el-form-item label="采购单摘要">
          <el-descriptions :column="4" border size="small" class="gr-create__desc">
            <el-descriptions-item label="单号">{{ po.po_no }}</el-descriptions-item>
            <el-descriptions-item label="状态">{{ poStatusLabel(po.status) }}</el-descriptions-item>
            <el-descriptions-item label="供应商">{{ po.supplier_id ?? '-' }}</el-descriptions-item>
            <el-descriptions-item label="入库仓库">{{ po.warehouse_id }}</el-descriptions-item>
            <el-descriptions-item label="订购总量">{{ po.total_qty }}</el-descriptions-item>
            <el-descriptions-item label="已收量">{{ po.received_qty }}</el-descriptions-item>
            <el-descriptions-item label="总金额">{{ po.total_amount }}</el-descriptions-item>
            <el-descriptions-item label="期望到货">
              {{ po.expected_arrival_date ?? '-' }}
            </el-descriptions-item>
          </el-descriptions>
        </el-form-item>

        <el-alert
          v-if="!canReceive"
          type="error"
          :closable="false"
          show-icon
          class="gr-create__tip"
          :title="`当前采购单状态为「${poStatusLabel(po.status)}」，服务端只接受 ORDERED / PARTIALLY_RECEIVED 登记到货（否则 PO-9003）。请先到采购单详情页确认并下单。`"
        />

        <el-form-item label="到货日期（必填）">
          <el-date-picker
            v-model="receiptDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择到货业务日期"
            style="width: 200px"
            data-testid="gr-create-date"
          />
        </el-form-item>

        <el-form-item label="备注（可选）">
          <el-input
            v-model="remark"
            type="textarea"
            :rows="2"
            maxlength="500"
            show-word-limit
            placeholder="如：首批到货，包装完好"
            data-testid="gr-create-remark"
          />
        </el-form-item>

        <el-form-item label="到货明细（必填）">
          <el-table
            :data="lines"
            border
            size="small"
            data-testid="gr-create-lines"
            class="gr-create__lines"
          >
            <el-table-column label="采购明细 ID" width="120">
              <template #default="{ row }">{{ row.purchase_order_item_id }}</template>
            </el-table-column>
            <el-table-column label="SKU" width="110">
              <template #default="{ row }">{{ row.sku_id }}</template>
            </el-table-column>
            <el-table-column label="订购量" width="110" align="right">
              <template #default="{ row }">{{ row.ordered_qty }}</template>
            </el-table-column>
            <el-table-column label="已收量" width="110" align="right">
              <template #default="{ row }">{{ row.received_before }}</template>
            </el-table-column>
            <el-table-column label="待收量" width="110" align="right">
              <template #default="{ row }">
                <span :class="{ 'gr-create__done': !isPositiveDecimal(row.remaining) }">
                  {{ row.remaining }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="本次到货量" width="150">
              <template #default="{ row }">
                <el-input
                  v-model="row.receive_qty"
                  placeholder="如 40.000"
                  :data-testid="`gr-create-qty-${row.purchase_order_item_id}`"
                />
              </template>
            </el-table-column>
            <el-table-column label="到货单价" width="150">
              <template #default="{ row }">
                <el-input
                  v-model="row.unit_price"
                  placeholder="如 20.0000"
                  :data-testid="`gr-create-price-${row.purchase_order_item_id}`"
                />
              </template>
            </el-table-column>
            <el-table-column label="批号（可选）" min-width="140">
              <template #default="{ row }">
                <el-input v-model="row.batch_no" placeholder="供应方批号" />
              </template>
            </el-table-column>
            <el-table-column label="一键" width="90" align="center">
              <template #default="{ row }">
                <el-button
                  link
                  type="primary"
                  :disabled="!isPositiveDecimal(row.remaining)"
                  :data-testid="`gr-create-fill-${row.purchase_order_item_id}`"
                  @click="fillAll(row)"
                >
                  收满
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-form-item>
      </template>
    </el-form>

    <template #footer>
      <el-button data-testid="gr-create-cancel" @click="visible = false">取消</el-button>
      <el-button
        type="primary"
        :loading="submitting"
        :disabled="!po || !canReceive"
        data-testid="gr-create-submit"
        @click="onSubmit"
      >
        登记到货（草稿）
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
// 到货登记弹窗（M8-R200；`POST /purchasing/receipts`）。
//
// ★ 红线：**数量 / 单价 / 供应商一律从采购单读，界面不提供改供应商、不改仓库**
//   （服务端要求 `warehouse_id` 必须等于采购单的仓库，否则 COM-0001）。
// ★ **不改库存**：本动作只生成 DRAFT；`PURCHASE_INBOUND` 流水要等到详情页「确认到货」。
// ★ 「待收量」是**只读派生值**（订购量 − 已收量），用 `utils/decimal` 的精确整数减法算，
//   ❌ 不经过 `Number`（口径铁律 ④）；提交时**不发送**该字段，服务端自己重算超额守卫。
// ★ `received_qty` / `unit_price` 以**字符串**原样提交（服务端按 scale 3/4 归一），
//   `unit_price` 必填且**服务端不用采购价兜底**（采购价 ≠ 到货价是真实业务场景）。
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { createGoodsReceipt, getPurchaseOrder } from '@/api/purchasing'
import type { CreateGoodsReceiptItem, PurchaseOrderDetail } from '@/types/purchasing'
import { describeError } from '@/utils/error'
import { isPositiveDecimal, subtractDecimal } from '@/utils/decimal'
import { createIdempotencyIntent } from '@/utils/idempotency'
import { toPositiveInt } from '@/views/sales/salesQuery'
import { poStatusLabel } from './purchasingStatus'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  created: [goodsReceiptId: number, receiptNo: string, replayed: boolean]
}>()

const visible = computed<boolean>({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value),
})

/** 到货明细编辑行（含只读派生列）。 */
interface ReceiptLine {
  purchase_order_item_id: number
  sku_id: number
  ordered_qty: string
  received_before: string
  /** 待收量（`ordered_qty - received_before`，精确十进制减法，仅展示）。 */
  remaining: string
  receive_qty: string
  unit_price: string
  batch_no: string
}

const poIdInput = ref('')
const poLoading = ref(false)
const poError = ref('')
const po = ref<PurchaseOrderDetail | null>(null)
const lines = ref<ReceiptLine[]>([])
const receiptDate = ref(todayIso())
const remark = ref('')
const submitting = ref(false)
/** 一次「登记到货」意图一个幂等键：失败重试复用，成功/放弃后重置。 */
const intent = createIdempotencyIntent()

/** 本地日期 `YYYY-MM-DD`（`toISOString()` 是 UTC，跨时区会差一天，故手工拼）。 */
function todayIso(): string {
  const now = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
}

/** 服务端只接受这两个状态登记到货（`GoodsReceiptAppService#create` 校验 ②）。 */
const canReceive = computed(
  () => po.value?.status === 'ORDERED' || po.value?.status === 'PARTIALLY_RECEIVED',
)

function reset(): void {
  poIdInput.value = ''
  poError.value = ''
  po.value = null
  lines.value = []
  receiptDate.value = todayIso()
  remark.value = ''
  intent.reset()
}

// 每次打开都是全新一次意图（避免复用上次的幂等键导致 COM-0009）
watch(
  () => props.modelValue,
  (open) => {
    if (open) reset()
  },
)

async function loadPurchaseOrder(): Promise<void> {
  const id = toPositiveInt(poIdInput.value)
  if (!id) {
    ElMessage.warning('请输入正确的采购单 ID（正整数）')
    return
  }
  poLoading.value = true
  poError.value = ''
  po.value = null
  lines.value = []
  try {
    const detail = await getPurchaseOrder(id)
    po.value = detail
    lines.value = detail.items.map((item) => {
      const remaining = subtractDecimal(item.ordered_qty, item.received_qty)
      return {
        purchase_order_item_id: item.id,
        sku_id: item.sku_id,
        ordered_qty: item.ordered_qty,
        received_before: item.received_qty,
        remaining,
        // 默认整单收满（★ 只是把「待收量」原样搬进输入框，不做任何换算）
        receive_qty: isPositiveDecimal(remaining) ? remaining : '',
        // 默认取采购单价，可改（到货价 ≠ 采购价是合法业务场景）
        unit_price: item.unit_price,
        batch_no: '',
      }
    })
  } catch (error) {
    poError.value = describeError(error)
  } finally {
    poLoading.value = false
  }
}

function fillAll(row: ReceiptLine): void {
  row.receive_qty = row.remaining
}

/** 校验：只提交「填了数量」的行；`unit_price` 必填。 */
function buildItems(): CreateGoodsReceiptItem[] | null {
  const items: CreateGoodsReceiptItem[] = []
  for (const line of lines.value) {
    const qty = (line.receive_qty ?? '').trim()
    if (!qty) continue
    const price = (line.unit_price ?? '').trim()
    if (!price) {
      ElMessage.warning(`明细 #${line.purchase_order_item_id} 的到货单价必填`)
      return null
    }
    items.push({
      purchase_order_item_id: line.purchase_order_item_id,
      received_qty: qty,
      unit_price: price,
      ...(line.batch_no.trim() ? { batch_no: line.batch_no.trim() } : {}),
    })
  }
  if (items.length === 0) {
    ElMessage.warning('请至少填写一条明细的「本次到货量」')
    return null
  }
  return items
}

async function onSubmit(): Promise<void> {
  if (!po.value) return
  if (!canReceive.value) {
    ElMessage.warning('该采购单当前状态不允许登记到货，请先确认并下单')
    return
  }
  if (!receiptDate.value) {
    ElMessage.warning('到货日期必填')
    return
  }
  const items = buildItems()
  if (!items) return

  submitting.value = true
  try {
    const { data, replayed } = await createGoodsReceipt(
      {
        purchase_order_id: po.value.purchase_order_id,
        // ★ 必须等于采购单仓库，故直接用采购单的值（界面不提供修改）
        warehouse_id: po.value.warehouse_id,
        receipt_date: receiptDate.value,
        ...(remark.value.trim() ? { remark: remark.value.trim() } : {}),
        items,
      },
      intent.key(),
    )
    intent.reset()
    visible.value = false
    emit('created', data.goods_receipt_id, data.receipt_no, replayed)
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.gr-create__tip {
  margin-bottom: 12px;
}
.gr-create__form {
  margin-top: 4px;
}
.gr-create__load {
  margin-left: 8px;
}
.gr-create__hint {
  margin-left: 12px;
  color: #909399;
  font-size: 12px;
}
.gr-create__desc {
  width: 100%;
}
.gr-create__lines {
  width: 100%;
}
.gr-create__done {
  color: #909399;
}
</style>
