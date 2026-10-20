<template>
  <div class="run-detail">
    <el-card v-loading="statusLoading" shadow="never">
      <template #header>
        <div class="run-detail__head">
          <span class="run-detail__title">分析任务 {{ run?.run_no || runId }}</span>
          <el-tag v-if="run" :type="statusTagType(run.status)" size="small">
            {{ statusLabel(run.status) }}
          </el-tag>
          <span class="run-detail__subtitle">轮询 2s→10s 指数退避；终态自动停止</span>
          <span class="run-detail__spacer" />
          <el-button size="small" @click="goList">返回列表</el-button>
          <el-button
            v-if="canCancel"
            size="small"
            type="danger"
            plain
            :loading="cancelling"
            @click="onCancel"
          >
            取消任务
          </el-button>
        </div>
      </template>

      <el-alert
        v-if="statusError"
        type="error"
        :closable="false"
        show-icon
        :title="statusError"
        class="run-detail__alert"
      />

      <template v-if="run">
        <el-progress
          :percentage="run.progress ?? 0"
          :status="progressStatus"
          :stroke-width="14"
          class="run-detail__progress"
        />

        <el-descriptions :column="3" border size="small" class="run-detail__desc">
          <el-descriptions-item label="运行 ID">{{ run.run_id }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ statusLabel(run.status) }}</el-descriptions-item>
          <el-descriptions-item label="当前步骤">{{ run.current_step || '-' }}</el-descriptions-item>
          <el-descriptions-item label="范围">{{ run.scope_type }}</el-descriptions-item>
          <el-descriptions-item label="仓库 ID">{{ run.warehouse_id }}</el-descriptions-item>
          <el-descriptions-item label="SKU 进度">
            {{ run.processed_skus }} / {{ run.total_skus }}
          </el-descriptions-item>
          <el-descriptions-item label="LLM 状态">{{ run.llm_status || '-' }}</el-descriptions-item>
          <el-descriptions-item label="风险 SKU 数">
            {{ run.risk_sku_count ?? '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="建议数">{{ run.suggestion_count ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="数据窗口">
            {{ run.data_start_date || '-' }} ~ {{ run.data_end_date || '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="开始时间">
            {{ formatDateTime(run.started_at || '') }}
          </el-descriptions-item>
          <el-descriptions-item label="结束时间">
            {{ formatDateTime(run.finished_at || '') }}
          </el-descriptions-item>
          <el-descriptions-item label="超时截止">
            {{ formatDateTime(run.timeout_at || '') }}
          </el-descriptions-item>
        </el-descriptions>

        <el-alert
          v-if="run.status === 'DEGRADED'"
          type="warning"
          :closable="false"
          show-icon
          class="run-detail__alert"
          title="LLM 解释降级：规则计算结果可用"
          :description="run.llm_error_message || 'LLM 解释未生成，风险等级与补货建议不受影响'"
        />
        <el-alert
          v-if="run.status === 'FAILED' || run.status === 'TIMEOUT'"
          type="error"
          :closable="false"
          show-icon
          class="run-detail__alert"
          :title="run.status === 'TIMEOUT' ? '任务已超时' : '任务执行失败'"
          description="可调整日期区间或 SKU 范围后重新创建分析任务。"
        />
        <el-alert
          v-if="run.status === 'CANCELLED'"
          type="info"
          :closable="false"
          show-icon
          class="run-detail__alert"
          title="任务已取消"
          description="已取消的任务不产出结果快照，可重新创建分析任务。"
        />
      </template>
      <el-empty v-else-if="!statusLoading" description="暂无任务状态" :image-size="80" />
    </el-card>

    <el-card v-if="run" shadow="never" class="run-detail__items">
      <template #header>
        <div class="run-detail__head">
          <span class="run-detail__title">结果快照</span>
          <span class="run-detail__subtitle">
            数量列原样展示后端字符串（如 "8.000"），前端不做精度换算
          </span>
        </div>
      </template>

      <el-alert
        v-if="!resultsAvailable"
        type="info"
        :closable="false"
        show-icon
        :title="resultsHint"
        class="run-detail__alert"
      />

      <template v-else>
        <el-form :inline="true" class="run-detail__filters">
          <el-form-item label="风险等级">
            <el-select
              v-model="itemsQuery.risk_level"
              clearable
              placeholder="全部"
              style="width: 150px"
              @change="onFilterChange"
            >
              <el-option v-for="level in RISK_LEVELS" :key="level" :label="level" :value="level" />
            </el-select>
          </el-form-item>
          <el-form-item label="排序">
            <el-select v-model="itemsQuery.sort" style="width: 210px" @change="onFilterChange">
              <el-option
                v-for="opt in ITEM_SORT_OPTIONS"
                :key="opt.value"
                :label="opt.label"
                :value="opt.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button :loading="itemsLoading" @click="loadItems">刷新</el-button>
          </el-form-item>
        </el-form>

        <el-alert
          v-if="itemsError"
          type="error"
          :closable="false"
          show-icon
          :title="itemsError"
          class="run-detail__alert"
        />

        <PageTable
          :columns="itemColumns"
          :rows="items"
          :loading="itemsLoading"
          :total="itemsTotal"
          :page="itemsQuery.page"
          :page-size="itemsQuery.pageSize"
          row-key="id"
          :actions-width="100"
          @page-change="onPageChange"
          @size-change="onSizeChange"
        >
          <template #onHand="{ row }">{{ row.on_hand_qty ?? '-' }}</template>
          <template #available="{ row }">{{ row.available_qty ?? '-' }}</template>
          <template #avgDailySales="{ row }">{{ row.avg_daily_sales ?? '-' }}</template>
          <template #qtySuggest="{ row }">{{ row.qty_suggest ?? '-' }}</template>
          <template #riskLevel="{ row }">
            <el-tag :type="riskTagType(row.risk_level)" size="small">
              {{ row.risk_level || '-' }}
            </el-tag>
          </template>
          <template #actions="{ row }">
            <el-button link type="primary" @click="openItem(row)">详情</el-button>
          </template>
        </PageTable>
      </template>
    </el-card>

    <el-dialog v-model="itemVisible" title="结果明细" width="760px">
      <template v-if="selectedItem">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="SKU">{{ selectedItem.sku_code || selectedItem.sku_id }}</el-descriptions-item>
          <el-descriptions-item label="仓库 ID">{{ selectedItem.warehouse_id }}</el-descriptions-item>
          <el-descriptions-item label="现有库存">{{ selectedItem.on_hand_qty ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="可用量">{{ selectedItem.available_qty ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="日均销量">{{ selectedItem.avg_daily_sales ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="CV">{{ selectedItem.cv ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="有效天数">
            {{ selectedItem.valid_days ?? '-' }} / {{ selectedItem.sales_window_days ?? '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="交期">
            {{ selectedItem.lead_time_days ?? '-' }}（{{ selectedItem.lead_time_source || '-' }}）
          </el-descriptions-item>
          <el-descriptions-item label="覆盖天数">{{ selectedItem.coverage_days ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="安全库存">{{ selectedItem.safety_stock_qty ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="基础补货量">{{ selectedItem.qty_base ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="建议补货量">{{ selectedItem.qty_suggest ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="风险等级">{{ selectedItem.risk_level || '-' }}</el-descriptions-item>
          <el-descriptions-item label="风险类型">
            {{ (selectedItem.risk_types || []).join(' / ') || '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="LLM 状态">{{ selectedItem.llm_status || '-' }}</el-descriptions-item>
          <el-descriptions-item label="数据质量">
            {{ qualityText(selectedItem) }}
          </el-descriptions-item>
        </el-descriptions>

        <p class="run-detail__label">LLM 解释</p>
        <p class="run-detail__text">{{ selectedItem.llm_explanation || '（无解释）' }}</p>

        <template v-if="selectedItem.warnings && selectedItem.warnings.length > 0">
          <p class="run-detail__label">警告</p>
          <ul class="run-detail__warnings">
            <li v-for="(warning, index) in selectedItem.warnings" :key="index">{{ warning }}</li>
          </ul>
        </template>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
// 分析任务详情 / 进度页（M4-T16）。
//
// ★ 轮询：仅中间态（PENDING/RUNNING）每 2s 起、指数退避至 10s；
//   终态（SUCCEEDED/DEGRADED/FAILED/TIMEOUT/CANCELLED）自动停；
//   页面卸载（onUnmounted）清 timer + abort 在途请求 —— 硬验收项。
// ★ 结果快照：`GET /runs/{id}/items` 分页 + risk_level 过滤 + 排序；
//   `ANL-7012`（progress<100）不是失败，提示后继续轮询。
// ★ 数量列原样展示后端字符串，禁止 Number()/parseFloat() 后再格式化（口径铁律 ④）。
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageTable from '@/components/common/PageTable.vue'
import { cancelAnalysisRun, getAnalysisRun, listAnalysisRunItems } from '@/api/analysis'
import type { AnalysisItemView, AnalysisRunStatusResponse } from '@/types/analysis'
import { isInFlightStatus, isTerminalStatus } from '@/types/analysis'
import type { TableColumn } from '@/types/ui'
import { usePolling } from '@/composables/usePolling'
import { useTeamStore } from '@/stores/team'
import { hasAnyRole } from '@/utils/permission'
import { describeError } from '@/utils/error'
import { formatDateTime } from '@/utils/format'
import { classifyAnalysisError } from './analysisError'
import {
  ITEM_SORT_OPTIONS,
  RISK_LEVELS,
  riskTagType,
  statusLabel,
  statusTagType,
} from './analysisStatus'

const route = useRoute()
const router = useRouter()
const teamStore = useTeamStore()

const runId = computed(() => String(route.params.id ?? ''))

const run = ref<AnalysisRunStatusResponse | null>(null)
const statusLoading = ref(false)
const statusError = ref('')
const cancelling = ref(false)

/** 取消按钮显隐（体验层）：与后端 `@RequireRole(OPERATOR+)` 对齐，服务端仍是安全边界。 */
const canCancel = computed(
  () => isInFlightStatus(run.value?.status) && hasAnyRole(teamStore.role, ['OWNER', 'ADMIN', 'OPERATOR', 'APPROVER']),
)

const progressStatus = computed<'success' | 'exception' | 'warning' | undefined>(() => {
  const status = run.value?.status
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'DEGRADED') return 'warning'
  if (status === 'FAILED' || status === 'TIMEOUT') return 'exception'
  return undefined
})

/** 是否展示结果快照（仅规则计算完成的终态）。 */
const resultsAvailable = computed(() => {
  const status = run.value?.status
  return (status === 'SUCCEEDED' || status === 'DEGRADED') && (run.value?.progress ?? 0) >= 100
})

const resultsHint = computed(() => {
  if (!run.value) return '任务完成后自动加载结果快照'
  if (isInFlightStatus(run.value.status)) return '任务运行中，完成后自动加载结果快照'
  return '任务未成功完成，无结果快照可展示'
})

async function fetchStatus(): Promise<void> {
  statusLoading.value = true
  try {
    const data = await getAnalysisRun(runId.value)
    run.value = data
    statusError.value = ''
    if (resultsAvailable.value) {
      await loadItems()
    }
  } catch (error) {
    const hint = classifyAnalysisError(error)
    if (hint.action === 'not-found') {
      statusError.value = hint.message
      polling.stop()
      ElMessage.error(hint.message)
      return
    }
    // 状态查询失败按瞬时错误处理：保留轮询，下一次继续重试
    ElMessage.error(describeError(error))
  } finally {
    statusLoading.value = false
  }
}

const polling = usePolling(fetchStatus, {
  immediate: true,
  shouldStop: () => isTerminalStatus(run.value?.status),
})

// ---------------------------------------------------------------- 结果明细
const items = ref<AnalysisItemView[]>([])
const itemsTotal = ref(0)
const itemsLoading = ref(false)
const itemsError = ref('')
const itemsQuery = reactive({
  page: 1,
  pageSize: 20,
  risk_level: '',
  sort: 'risk_level,desc',
})

const itemColumns: TableColumn[] = [
  { prop: 'sku_code', label: 'SKU', minWidth: 160 },
  { prop: 'warehouse_id', label: '仓库 ID', width: 100 },
  { prop: 'on_hand_qty', label: '现有库存', width: 110, align: 'right', slot: 'onHand' },
  { prop: 'available_qty', label: '可用量', width: 110, align: 'right', slot: 'available' },
  { prop: 'avg_daily_sales', label: '日均销量', width: 120, align: 'right', slot: 'avgDailySales' },
  { prop: 'lead_time_days', label: '交期(天)', width: 100, align: 'right' },
  { prop: 'coverage_days', label: '覆盖(天)', width: 100, align: 'right' },
  { prop: 'qty_suggest', label: '建议补货量', width: 130, align: 'right', slot: 'qtySuggest' },
  { prop: 'risk_level', label: '风险等级', width: 110, slot: 'riskLevel' },
  { prop: 'llm_status', label: 'LLM', width: 110 },
]

async function loadItems(): Promise<void> {
  itemsLoading.value = true
  itemsError.value = ''
  try {
    const result = await listAnalysisRunItems(runId.value, {
      page: itemsQuery.page,
      pageSize: itemsQuery.pageSize,
      risk_level: itemsQuery.risk_level || undefined,
      sort: itemsQuery.sort || undefined,
    })
    items.value = result.list
    itemsTotal.value = result.total
  } catch (error) {
    const hint = classifyAnalysisError(error)
    if (hint.action === 'continue-polling') {
      // ANL-7012：结果尚未生成 —— 不是失败，提示后继续轮询
      items.value = []
      itemsTotal.value = 0
      ElMessage.info(hint.message)
      if (!polling.stopped.value && isInFlightStatus(run.value?.status)) {
        polling.start()
      }
      return
    }
    itemsError.value = describeError(error)
    ElMessage.error(itemsError.value)
  } finally {
    itemsLoading.value = false
  }
}

function onFilterChange(): void {
  itemsQuery.page = 1
  void loadItems()
}

function onPageChange(page: number): void {
  itemsQuery.page = page
  void loadItems()
}

function onSizeChange(pageSize: number): void {
  itemsQuery.pageSize = pageSize
  itemsQuery.page = 1
  void loadItems()
}

// ---------------------------------------------------------------- 明细弹窗
const itemVisible = ref(false)
const selectedItem = ref<AnalysisItemView | null>(null)

function openItem(row: AnalysisItemView): void {
  selectedItem.value = row
  itemVisible.value = true
}

function qualityText(item: AnalysisItemView): string {
  const quality = item.data_quality_json
  if (!quality) return '-'
  const level = String(quality.level ?? '-')
  const validDays = String(quality.valid_days ?? '-')
  const missingDays = String(quality.missing_days ?? '-')
  return `${level}（有效 ${validDays} 天 / 缺失 ${missingDays} 天）`
}

// ---------------------------------------------------------------- 取消
async function onCancel(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '取消后任务立即终止且不产出结果快照，确定取消吗？',
      '取消分析任务',
      { type: 'warning', confirmButtonText: '确定取消', cancelButtonText: '再等等' },
    )
  } catch {
    return
  }
  cancelling.value = true
  try {
    const result = await cancelAnalysisRun(runId.value)
    ElMessage.success(`已取消，取消时间 ${formatDateTime(result.cancelled_at || '')}`)
    await fetchStatus()
  } catch (error) {
    const hint = classifyAnalysisError(error)
    if (hint.action === 'refresh-status') {
      ElMessage.warning(hint.message)
      await fetchStatus()
    } else {
      ElMessage.error(describeError(error))
    }
  } finally {
    cancelling.value = false
  }
}

function goList(): void {
  void router.push({ name: 'analysis-runs' })
}

onMounted(() => {
  polling.start()
})
</script>

<style scoped>
.run-detail__head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.run-detail__title {
  font-weight: 600;
}
.run-detail__subtitle {
  color: #909399;
  font-size: 12px;
}
.run-detail__spacer {
  flex: 1;
}
.run-detail__progress {
  margin: 4px 0 16px;
}
.run-detail__desc {
  margin-bottom: 12px;
}
.run-detail__alert {
  margin-bottom: 12px;
}
.run-detail__items {
  margin-top: 16px;
}
.run-detail__filters {
  margin-bottom: 4px;
}
.run-detail__label {
  margin: 14px 0 6px;
  font-weight: 600;
  font-size: 13px;
}
.run-detail__text {
  margin: 0;
  color: #303133;
  line-height: 1.7;
  font-size: 13px;
}
.run-detail__warnings {
  margin: 0;
  padding-left: 18px;
  color: #e6a23c;
  font-size: 13px;
  line-height: 1.7;
}
</style>
