<template>
  <div class="rpl-list" data-testid="rpl-list">
    <el-card shadow="never">
      <template #header>
        <div class="rpl-list__head">
          <span class="rpl-list__title">{{ pageTitle }}</span>
          <span class="rpl-list__subtitle">共 {{ total }} 条</span>
          <span class="rpl-list__spacer" />
          <el-button
            v-if="canConvert"
            type="primary"
            :disabled="selectedIds.length === 0"
            data-testid="rpl-convert-open"
            @click="openConvert"
          >
            勾选转采购单（已选 {{ selectedIds.length }}）
          </el-button>
        </div>
      </template>

      <el-alert type="info" :closable="false" show-icon class="rpl-list__tip">
        <template #title>
          建议链路：AI 分析算出 <b>补货量</b> → 生成建议（待提交）→ 提交审核 → 审批通过 → 勾选转采购单 →
          采购单确认/下单 → 登记到货 → 确认到货（<b>确认到货那一刻才真正入库</b>）。
        </template>
      </el-alert>

      <el-form :inline="true" class="rpl-list__filters" @submit.prevent>
        <el-form-item label="状态">
          <el-select
            v-model="filterStatus"
            clearable
            placeholder="全部"
            style="width: 220px"
            data-testid="rpl-filter-status"
            @change="onSearch"
          >
            <el-option
              v-for="status in REPLENISHMENT_STATUSES"
              :key="status"
              :label="replenishmentStatusLabel(status)"
              :value="status"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="风险等级">
          <el-select
            v-model="filterRiskLevel"
            clearable
            placeholder="全部"
            style="width: 190px"
            data-testid="rpl-filter-risk"
            @change="onSearch"
          >
            <el-option
              v-for="level in RISK_LEVELS"
              :key="level"
              :label="riskLevelLabel(level)"
              :value="level"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="分析轮次 ID">
          <el-input
            v-model="filterRunId"
            placeholder="如 18（留空=全部）"
            style="width: 180px"
            data-testid="rpl-filter-run"
            @keyup.enter="onSearch"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" data-testid="rpl-search" @click="onSearch">查询</el-button>
          <el-button data-testid="rpl-reset" @click="onReset">重置</el-button>
        </el-form-item>
      </el-form>

      <div v-loading="loading">
        <el-table
          v-if="list.length > 0"
          ref="tableRef"
          :data="list"
          border
          stripe
          row-key="id"
          data-testid="rpl-table"
          @selection-change="onSelectionChange"
        >
          <el-table-column
            type="selection"
            width="46"
            :selectable="isSelectable"
            reserve-selection
          />
          <el-table-column prop="id" label="建议 ID" width="90" />
          <el-table-column label="SKU（商品规格）" width="140">
            <template #default="{ row }">{{ row.sku_id }}</template>
          </el-table-column>
          <el-table-column label="仓库" width="90">
            <template #default="{ row }">{{ row.warehouse_id }}</template>
          </el-table-column>
          <el-table-column label="状态" width="160">
            <template #default="{ row }">
              <el-tag :type="replenishmentStatusTagType(row.status)" size="small">
                {{ replenishmentStatusShort(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="风险等级" width="130">
            <template #default="{ row }">
              <el-tag :type="riskLevelTagType(row.risk_level)" size="small" effect="plain">
                {{ riskLevelLabel(row.risk_level) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="规则建议量" width="120" align="right">
            <template #default="{ row }">{{ row.suggested_qty }}</template>
          </el-table-column>
          <el-table-column label="当前数量" width="120" align="right">
            <template #default="{ row }">{{ row.qty }}</template>
          </el-table-column>
          <el-table-column label="供应商" width="90">
            <template #default="{ row }">{{ row.supplier_id ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="采购单价" width="110" align="right">
            <template #default="{ row }">{{ row.purchase_price ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="期望到货" width="120">
            <template #default="{ row }">{{ row.expected_arrival_date ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="创建时间" min-width="170">
            <template #default="{ row }">{{ formatDateTime(row.created_at) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="230" align="center" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" data-testid="rpl-row-detail" @click="openDetail(row)">
                详情
              </el-button>
              <el-button
                v-if="canSubmit && row.status === 'SUGGESTED'"
                link
                type="primary"
                data-testid="rpl-row-submit"
                @click="onSubmit(row)"
              >
                提交审核
              </el-button>
              <el-button
                v-if="canSubmit && row.status === 'PENDING_REVIEW'"
                link
                type="warning"
                data-testid="rpl-row-withdraw"
                @click="onWithdraw(row)"
              >
                撤回
              </el-button>
              <el-button
                v-if="canApprove && row.status === 'PENDING_REVIEW'"
                link
                type="success"
                data-testid="rpl-row-approve"
                @click="onApprove(row)"
              >
                审批通过
              </el-button>
              <el-button
                v-if="canApprove && row.status === 'PENDING_REVIEW'"
                link
                type="danger"
                data-testid="rpl-row-reject"
                @click="onReject(row)"
              >
                驳回
              </el-button>
              <el-button
                v-if="canSubmit && (row.status === 'SUGGESTED' || row.status === 'APPROVED')"
                link
                type="info"
                data-testid="rpl-row-cancel"
                @click="onCancel(row)"
              >
                作废
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-empty
          v-else-if="!loading"
          data-testid="rpl-empty"
          description="暂无补货建议（先跑一次 AI 分析）"
          :image-size="90"
        />

        <el-pagination
          v-if="total > 0"
          class="rpl-list__pager"
          background
          layout="total, sizes, prev, pager, next, jumper"
          :current-page="query.page"
          :page-size="query.pageSize"
          :page-sizes="[10, 20, 50, 100]"
          :total="total"
          data-testid="rpl-pager"
          @current-change="onPageChange"
          @size-change="onSizeChange"
        />
      </div>
    </el-card>

    <!-- 详情 + 审核时间线 -->
    <el-dialog v-model="detailVisible" title="补货建议详情" width="880px" destroy-on-close>
      <div v-loading="detailLoading">
        <template v-if="detail">
          <el-descriptions :column="3" border size="small" data-testid="rpl-detail-desc">
            <el-descriptions-item label="建议 ID">{{ detail.id }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag :type="replenishmentStatusTagType(detail.status)" size="small">
                {{ replenishmentStatusShort(detail.status) }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="风险等级">
              {{ riskLevelLabel(detail.risk_level) }}
            </el-descriptions-item>
            <el-descriptions-item label="SKU（商品规格）">{{ detail.sku_id }}</el-descriptions-item>
            <el-descriptions-item label="商品 ID">{{ detail.product_id ?? '-' }}</el-descriptions-item>
            <el-descriptions-item label="仓库 ID">{{ detail.warehouse_id }}</el-descriptions-item>
            <el-descriptions-item label="规则建议量（Q_base）">
              {{ detail.suggested_qty }}
            </el-descriptions-item>
            <el-descriptions-item label="当前数量">{{ detail.qty }}</el-descriptions-item>
            <el-descriptions-item label="乐观锁版本">{{ detail.version }}</el-descriptions-item>
            <el-descriptions-item label="分析轮次（run_id）">{{ detail.run_id }}</el-descriptions-item>
            <el-descriptions-item label="来源分析明细 ID">
              {{ detail.source_analysis_item_id }}
            </el-descriptions-item>
            <el-descriptions-item label="供应商 ID">{{ detail.supplier_id ?? '-' }}</el-descriptions-item>
            <el-descriptions-item label="采购单价">{{ detail.purchase_price ?? '-' }}</el-descriptions-item>
            <el-descriptions-item label="期望到货日">
              {{ detail.expected_arrival_date ?? '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="已生成采购明细">
              {{ detail.converted_po_item_ids ?? '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="提交时间">
              {{ formatDateTime(detail.submitted_at || '') }}
            </el-descriptions-item>
            <el-descriptions-item label="审批时间">
              {{ formatDateTime(detail.approved_at || '') }}
            </el-descriptions-item>
            <el-descriptions-item label="关闭时间">
              {{ formatDateTime(detail.closed_at || '') }}
            </el-descriptions-item>
            <el-descriptions-item label="判定依据" :span="3">
              {{ detail.reason || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="备注" :span="3">
              {{ detail.remark || '-' }}
            </el-descriptions-item>
          </el-descriptions>

          <p class="rpl-list__label">审核时间线</p>
          <el-table
            v-if="reviews.length > 0"
            :data="reviews"
            border
            size="small"
            data-testid="rpl-detail-reviews"
          >
            <el-table-column label="动作" width="110">
              <template #default="{ row }">{{ reviewActionLabel(row.action) }}</template>
            </el-table-column>
            <el-table-column label="状态变化" min-width="230">
              <template #default="{ row }">
                {{ replenishmentStatusShort(row.from_status) }} →
                {{ replenishmentStatusShort(row.to_status) }}
              </template>
            </el-table-column>
            <el-table-column label="数量变化" width="150">
              <template #default="{ row }">
                <span v-if="row.qty_after">{{ row.qty_before ?? '-' }} → {{ row.qty_after }}</span>
                <span v-else>-</span>
              </template>
            </el-table-column>
            <el-table-column label="操作人 / 角色" width="150">
              <template #default="{ row }">
                {{ row.reviewer_id ?? '-' }} / {{ row.reviewer_role ?? '-' }}
              </template>
            </el-table-column>
            <el-table-column label="时间" width="170">
              <template #default="{ row }">{{ formatDateTime(row.occurred_at) }}</template>
            </el-table-column>
            <el-table-column label="意见" min-width="160">
              <template #default="{ row }">{{ row.comment || '-' }}</template>
            </el-table-column>
          </el-table>
          <el-empty v-else description="暂无审核记录" :image-size="60" />
        </template>
        <el-empty v-else-if="!detailLoading" description="加载失败" :image-size="70" />
      </div>
    </el-dialog>

    <!-- 勾选转采购单 -->
    <el-dialog v-model="convertVisible" title="由已审批建议生成采购单" width="820px" destroy-on-close>
      <el-alert type="info" :closable="false" show-icon class="rpl-list__tip">
        <template #title>
          数量 / 单价 / 供应商一律取自建议，界面 <b>不可改</b>（服务端不接受覆盖）；生成的是
          <b>草稿（DRAFT）</b> 采购单，还需到「采购单」页确认、下单。
        </template>
      </el-alert>

      <el-table :data="selectedRows" border size="small" data-testid="rpl-convert-rows">
        <el-table-column prop="id" label="建议 ID" width="90" />
        <el-table-column label="SKU" width="110">
          <template #default="{ row }">{{ row.sku_id }}</template>
        </el-table-column>
        <el-table-column label="仓库" width="90">
          <template #default="{ row }">{{ row.warehouse_id }}</template>
        </el-table-column>
        <el-table-column label="数量" width="120" align="right">
          <template #default="{ row }">{{ row.qty }}</template>
        </el-table-column>
        <el-table-column label="供应商" width="100">
          <template #default="{ row }">{{ row.supplier_id ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="采购单价" width="120" align="right">
          <template #default="{ row }">{{ row.purchase_price ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="风险" min-width="110">
          <template #default="{ row }">{{ riskLevelShort(row.risk_level) }}</template>
        </el-table-column>
      </el-table>

      <el-form label-width="150px" class="rpl-list__convert-form">
        <el-form-item label="期望到货日（可选）">
          <el-date-picker
            v-model="convertForm.expected_arrival_date"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="不填则由服务端按交期推导"
            style="width: 240px"
            data-testid="rpl-convert-eta"
          />
        </el-form-item>
        <el-form-item label="备注（可选）">
          <el-input
            v-model="convertForm.remark"
            type="textarea"
            :rows="2"
            maxlength="500"
            show-word-limit
            placeholder="如：季末补货，优先到仓 9003"
            data-testid="rpl-convert-remark"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button data-testid="rpl-convert-cancel" @click="convertVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="converting"
          data-testid="rpl-convert-submit"
          @click="onConvert"
        >
          生成采购单（草稿）
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
// 补货建议页（M8-R200；`GET/POST /replenishment/items...` + `POST /purchasing/orders/from-replenishment`）。
//
// 一个组件覆盖两条路由：
//   `/replenishment/suggestions`（任何已登录成员）→ 全部建议，可提交/撤回/审批/转采购单
//   `/replenishment/review`（仅 OWNER/ADMIN/APPROVER）→ 默认只看「待审批」队列
//
// ★ 红线：
//   - 状态机与业务规则**全在服务端**；本页只按当前状态 + 角色**显隐按钮**（体验层）；
//   - `suggested_qty` / `qty` / `purchase_price` 是字符串（scale 3/4），原样展示，
//     ❌ 不做 `Number()` / 千分位换算（口径铁律 ④）；
//   - 转采购单**不改库存**（口径铁律 ④：只有「确认到货」才入库）。
//
// ★ 角色口径（与后端 `@RequireRole` 逐字对齐）：
//   submit / withdraw / cancel → OWNER, ADMIN, OPERATOR, APPROVER
//   approve / reject           → OWNER, ADMIN, APPROVER（**不含 OPERATOR**）
//   from-replenishment         → OWNER, ADMIN, APPROVER
//
// ★ `RPL-8012`（同 SKU×仓库还有其它已审批建议）是**有意的防重复采购保护**，不是缺陷：
//   首次 409 → 弹出冲突清单 → 用户确认后带 `conflict_acknowledged=true` +
//   `acknowledged_item_ids`（须与服务端当前冲突集**集合相等**）重试，且**复用同一幂等键**。
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  approveReplenishment,
  cancelReplenishment,
  getReplenishmentItem,
  listReplenishmentItems,
  listReplenishmentReviews,
  rejectReplenishment,
  submitReplenishment,
  withdrawReplenishment,
} from '@/api/replenishment'
import { createPurchaseOrderFromReplenishment } from '@/api/purchasing'
import type {
  ReplenishmentConflictItem,
  ReplenishmentItem,
  ReplenishmentQuery,
  ReplenishmentReview,
  ReplenishmentStatus,
  RiskLevel,
} from '@/types/replenishment'
import { useTeamStore } from '@/stores/team'
import { hasAnyRole } from '@/utils/permission'
import { describeError, errorCodeOf } from '@/utils/error'
import { formatDateTime } from '@/utils/format'
import { createIdempotencyIntent } from '@/utils/idempotency'
import { replenishmentStatusLabel, riskLevelLabel } from '@/utils/terms'
import { toPositiveInt } from '@/views/sales/salesQuery'
import {
  REPLENISHMENT_STATUSES,
  RISK_LEVELS,
  replenishmentStatusShort,
  replenishmentStatusTagType,
  reviewActionLabel,
  riskLevelShort,
  riskLevelTagType,
} from './replenishmentStatus'

/** 提交/撤回/作废的写角色（含 OPERATOR）。 */
const SUBMIT_ROLES = ['OWNER', 'ADMIN', 'OPERATOR', 'APPROVER']
/** 审批 + 转采购单的角色（Contract §10.8：**不含 OPERATOR**）。 */
const APPROVE_ROLES = ['OWNER', 'ADMIN', 'APPROVER']

const route = useRoute()
const router = useRouter()
const teamStore = useTeamStore()

/** 审核队列模式（路由决定；仅影响默认筛选与标题）。 */
const isReviewMode = computed(() => route.name === 'replenishment-review')
const pageTitle = computed(() => (isReviewMode.value ? '补货审核（待审批队列）' : '补货建议'))

const canSubmit = computed(() => hasAnyRole(teamStore.role, SUBMIT_ROLES))
const canApprove = computed(() => hasAnyRole(teamStore.role, APPROVE_ROLES))
const canConvert = computed(() => hasAnyRole(teamStore.role, APPROVE_ROLES))

const list = ref<ReplenishmentItem[]>([])
const total = ref(0)
const loading = ref(false)
const tableRef = ref<{ clearSelection: () => void } | null>(null)

const query = reactive<ReplenishmentQuery>({ page: 1, pageSize: 20 })
const filterStatus = ref<ReplenishmentStatus | ''>(isReviewMode.value ? 'PENDING_REVIEW' : '')
const filterRiskLevel = ref<RiskLevel | ''>('')
const filterRunId = ref('')

const selectedRows = ref<ReplenishmentItem[]>([])
const selectedIds = computed(() => selectedRows.value.map((row) => row.id))

/** 只有 `APPROVED` 可被勾选（其余状态转采购单必被服务端拒）。 */
function isSelectable(row: ReplenishmentItem): boolean {
  return canConvert.value && row.status === 'APPROVED'
}

function onSelectionChange(rows: ReplenishmentItem[]): void {
  selectedRows.value = rows
}

/** 详情弹窗。 */
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<ReplenishmentItem | null>(null)
const reviews = ref<ReplenishmentReview[]>([])

/** 转采购单弹窗。 */
const convertVisible = ref(false)
const converting = ref(false)
const convertForm = reactive<{ expected_arrival_date: string; remark: string }>({
  expected_arrival_date: '',
  remark: '',
})
/** 一次「转采购单」意图一个幂等键：409 冲突确认后重试**复用同一键**。 */
const convertIntent = createIdempotencyIntent()

function applyFilters(): void {
  query.status = filterStatus.value || undefined
  query.risk_level = filterRiskLevel.value || undefined
  query.run_id = toPositiveInt(filterRunId.value)
}

async function fetchList(): Promise<void> {
  loading.value = true
  try {
    const result = await listReplenishmentItems({ ...query })
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
  filterStatus.value = isReviewMode.value ? 'PENDING_REVIEW' : ''
  filterRiskLevel.value = ''
  filterRunId.value = ''
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

/** 动作后统一刷新（不本地改状态充数：服务端会重算 `version` 与派生字段）。 */
async function refreshAfterAction(): Promise<void> {
  await fetchList()
}

// ---------------------------------------------------------------- 行内动作

async function onSubmit(row: ReplenishmentItem): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `把建议 #${row.id}（SKU ${row.sku_id}，数量 ${row.qty}）提交审核？提交后才可被审批。`,
      '提交审核',
      { type: 'info', confirmButtonText: '提交', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    const result = await submitReplenishment(row.id)
    ElMessage.success(`已提交审核（${result.from_status} → ${result.to_status}）`)
    await refreshAfterAction()
  } catch (error) {
    ElMessage.error(describeError(error))
  }
}

async function onWithdraw(row: ReplenishmentItem): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `撤回建议 #${row.id} 的提交？撤回后回到「待提交」，可再次编辑与提交。`,
      '撤回提交',
      { type: 'warning', confirmButtonText: '撤回', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    const result = await withdrawReplenishment(row.id)
    ElMessage.success(`已撤回（${result.from_status} → ${result.to_status}）`)
    await refreshAfterAction()
  } catch (error) {
    ElMessage.error(describeError(error))
  }
}

async function onApprove(row: ReplenishmentItem): Promise<void> {
  let comment = ''
  try {
    const { value } = await ElMessageBox.prompt(
      `审批通过建议 #${row.id}（SKU ${row.sku_id}，数量 ${row.qty}）？通过后可转采购单。可填审批意见（选填）。`,
      '审批通过',
      {
        type: 'success',
        confirmButtonText: '通过',
        cancelButtonText: '取消',
        inputPlaceholder: '审批意见（选填，≤500 字）',
        inputType: 'textarea',
        inputValidator: (val: string) => (val ?? '').length <= 500 || '最多 500 字',
      },
    )
    comment = (value ?? '').trim()
  } catch {
    return
  }
  try {
    await approveReplenishment(row.id, row.version, comment || undefined)
    ElMessage.success('已审批通过，可勾选转采购单')
    await refreshAfterAction()
  } catch (error) {
    // RPL-8003：别人先改了这条建议 ⇒ 版本已变，刷新后重试
    if (errorCodeOf(error) === 'RPL-8003') {
      ElMessage.warning('该建议已被他人修改（版本冲突），已刷新列表，请确认后重试')
      await refreshAfterAction()
      return
    }
    ElMessage.error(describeError(error))
  }
}

async function onReject(row: ReplenishmentItem): Promise<void> {
  let comment = ''
  try {
    const { value } = await ElMessageBox.prompt(
      `驳回建议 #${row.id}（SKU ${row.sku_id}）？驳回是终态，不可再提交。请填写驳回原因（必填）。`,
      '驳回',
      {
        type: 'warning',
        confirmButtonText: '驳回',
        cancelButtonText: '取消',
        inputPlaceholder: '驳回原因（必填，≤500 字）',
        inputType: 'textarea',
        inputValidator: (val: string) =>
          (val ?? '').trim().length > 0 ? true : '驳回原因必填',
      },
    )
    comment = (value ?? '').trim()
  } catch {
    return
  }
  try {
    await rejectReplenishment(row.id, row.version, comment)
    ElMessage.success('已驳回')
    await refreshAfterAction()
  } catch (error) {
    if (errorCodeOf(error) === 'RPL-8003') {
      ElMessage.warning('该建议已被他人修改（版本冲突），已刷新列表，请确认后重试')
      await refreshAfterAction()
      return
    }
    ElMessage.error(describeError(error))
  }
}

async function onCancel(row: ReplenishmentItem): Promise<void> {
  let comment = ''
  try {
    const { value } = await ElMessageBox.prompt(
      `作废建议 #${row.id}？作废后不再参与采购。可填作废原因（选填）。`,
      '作废建议',
      {
        type: 'warning',
        confirmButtonText: '作废',
        cancelButtonText: '取消',
        inputPlaceholder: '作废原因（选填，≤500 字）',
        inputType: 'textarea',
        inputValidator: (val: string) => (val ?? '').length <= 500 || '最多 500 字',
      },
    )
    comment = (value ?? '').trim()
  } catch {
    return
  }
  try {
    const result = await cancelReplenishment(row.id, comment || undefined)
    ElMessage.success(`已作废（${result.from_status} → ${result.to_status}）`)
    await refreshAfterAction()
  } catch (error) {
    ElMessage.error(describeError(error))
  }
}

// ---------------------------------------------------------------- 详情

async function openDetail(row: ReplenishmentItem): Promise<void> {
  detailVisible.value = true
  detail.value = null
  reviews.value = []
  detailLoading.value = true
  try {
    // ★ 重新拉详情而非复用列表行：列表不含 `remark`/`converted_*` 等字段的完整快照保证
    detail.value = await getReplenishmentItem(row.id)
    const page = await listReplenishmentReviews(row.id)
    reviews.value = page.list
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    detailLoading.value = false
  }
}

// ---------------------------------------------------------------- 转采购单

function openConvert(): void {
  if (selectedRows.value.length === 0) {
    ElMessage.warning('请先勾选状态为「已审批」的建议')
    return
  }
  const warehouses = new Set(selectedRows.value.map((row) => row.warehouse_id))
  if (warehouses.size > 1) {
    ElMessage.warning('所选建议不在同一仓库。一张采购单只能有一个到货仓库，请按仓库分批生成。')
    return
  }
  const owners = new Set(selectedRows.value.map((row) => row.supplier_id ?? 0))
  if (owners.size > 1) {
    ElMessage.warning('所选建议的供应商不一致。一张采购单只能有一个供应商，请按供应商分批生成。')
    return
  }
  convertForm.expected_arrival_date = ''
  convertForm.remark = ''
  convertIntent.reset()
  convertVisible.value = true
}

/** 从 `RPL-8012` 的 `details[]` 里取出冲突清单（`field=conflict_items`，载体在 `rejectedValue`）。 */
function extractConflicts(error: unknown): {
  message: string
  items: ReplenishmentConflictItem[]
} {
  const err = error as { message?: string; details?: { field: string; message: string; rejectedValue?: unknown }[] }
  const items: ReplenishmentConflictItem[] = []
  for (const detail of err?.details ?? []) {
    if (detail.field !== 'conflict_items') continue
    const raw = detail.rejectedValue as ReplenishmentConflictItem | undefined
    if (raw && typeof raw.replenishment_item_id === 'number') {
      items.push(raw)
    }
  }
  return { message: err?.message || '该 SKU 在本仓库还有其它已审批的补货建议', items }
}

async function onConvert(): Promise<void> {
  if (selectedRows.value.length === 0) return
  const warehouseId = selectedRows.value[0]!.warehouse_id
  const ids = [...selectedIds.value]
  const payload = {
    replenishment_item_ids: ids,
    warehouse_id: warehouseId,
    expected_arrival_date: convertForm.expected_arrival_date || null,
    remark: convertForm.remark.trim() || null,
  }

  convertIntent.reset()
  converting.value = true
  try {
    const { data, replayed } = await createPurchaseOrderFromReplenishment(
      payload,
      convertIntent.key(),
    )
    finishConvert(data.purchase_order_id, data.po_no, replayed)
  } catch (error) {
    if (errorCodeOf(error) === 'RPL-8012') {
      const conflict = extractConflicts(error)
      converting.value = false
      await resolveConflict(conflict, payload, warehouseId)
      return
    }
    ElMessage.error(describeError(error))
  } finally {
    converting.value = false
  }
}

/**
 * 冲突二次确认：把服务端返回的冲突清单原样展示，用户确认后带确认集重试。
 *
 * ★ 复用 `convertIntent` 的同一个幂等键（同一用户意图，避免重复建单）。
 * ★ `acknowledged_item_ids` 必须与服务端当前冲突集**集合相等**，故直接用服务端回传的 ID 列表。
 */
async function resolveConflict(
  conflict: { message: string; items: ReplenishmentConflictItem[] },
  payload: {
    replenishment_item_ids: number[]
    warehouse_id: number
    expected_arrival_date: string | null
    remark: string | null
  },
  warehouseId: number,
): Promise<void> {
  const lines = conflict.items
    .map(
      (item) =>
        `· 建议 #${item.replenishment_item_id}：SKU ${item.sku_id}，数量 ${item.qty}，风险 ${item.risk_level}`,
    )
    .join('<br/>')
  const html =
    `${conflict.message}<br/><br/><b>同 SKU × 仓库的其它已审批建议：</b><br/>${lines}<br/><br/>` +
    '继续将<b>额外</b>生成采购明细，可能造成重复采购。确认继续吗？'
  try {
    await ElMessageBox.confirm(html, '存在重复补货风险', {
      dangerouslyUseHTMLString: true,
      type: 'warning',
      confirmButtonText: '确认继续生成',
      cancelButtonText: '我改选建议',
    })
  } catch {
    ElMessage.info('已取消，未生成采购单')
    return
  }

  converting.value = true
  try {
    const { data, replayed } = await createPurchaseOrderFromReplenishment(
      {
        ...payload,
        warehouse_id: warehouseId,
        conflict_acknowledged: true,
        acknowledged_item_ids: conflict.items.map((item) => item.replenishment_item_id),
      },
      convertIntent.key(),
    )
    finishConvert(data.purchase_order_id, data.po_no, replayed)
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    converting.value = false
  }
}

function finishConvert(poId: number, poNo: string, replayed: boolean): void {
  convertIntent.reset()
  convertVisible.value = false
  selectedRows.value = []
  tableRef.value?.clearSelection()
  if (replayed) {
    ElMessage.warning(`该请求此前已处理，服务端返回首次结果：采购单 ${poNo}（未重复创建）`)
  } else {
    ElMessage.success(`已生成采购单 ${poNo}（草稿）。下一步：到「采购单」页确认并下单。`)
  }
  void fetchList()
  void router.push({ name: 'purchasing-order-detail', params: { id: String(poId) } })
}

// 两条路由复用同一组件：切换时按模式重置筛选并重查。
watch(isReviewMode, (review) => {
  filterStatus.value = review ? 'PENDING_REVIEW' : ''
  filterRiskLevel.value = ''
  filterRunId.value = ''
  onSearch()
})

onMounted(() => {
  applyFilters()
  void fetchList()
})
</script>

<style scoped>
.rpl-list__head {
  display: flex;
  align-items: center;
  gap: 12px;
}
.rpl-list__title {
  font-weight: 600;
}
.rpl-list__subtitle {
  color: #909399;
  font-size: 12px;
}
.rpl-list__spacer {
  flex: 1;
}
.rpl-list__tip {
  margin-bottom: 12px;
}
.rpl-list__filters {
  margin-bottom: 4px;
}
.rpl-list__pager {
  margin-top: 12px;
  justify-content: flex-end;
}
.rpl-list__label {
  margin: 16px 0 6px;
  font-weight: 600;
  font-size: 13px;
}
.rpl-list__convert-form {
  margin-top: 16px;
}
</style>
