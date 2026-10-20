<template>
  <div class="run-list">
    <el-card shadow="never">
      <template #header>
        <div class="run-list__head">
          <span class="run-list__title">分析任务</span>
          <span class="run-list__subtitle">
            共 {{ total }} 条；当前页有进行中任务时自动静默刷新
          </span>
          <span class="run-list__spacer" />
          <el-button type="primary" @click="goCreate">新建分析任务</el-button>
        </div>
      </template>

      <el-form :inline="true" class="run-list__filters">
        <el-form-item label="状态">
          <el-select
            v-model="filterStatus"
            clearable
            placeholder="全部"
            style="width: 170px"
            @change="onFilterChange"
          >
            <el-option
              v-for="status in RUN_STATUS_OPTIONS"
              :key="status"
              :label="statusLabel(status)"
              :value="status"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="创建日期">
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            unlink-panels
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            style="width: 260px"
            @change="onFilterChange"
          />
        </el-form-item>
        <el-form-item label="排序">
          <el-select v-model="sortValue" style="width: 200px" @change="onFilterChange">
            <el-option
              v-for="option in RUN_SORT_OPTIONS"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button @click="onReset">重置</el-button>
        </el-form-item>
      </el-form>

      <div v-loading="loading">
        <el-table v-if="list.length > 0" :data="list" border stripe size="default">
          <el-table-column label="运行编号" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="run-list__run-no">{{ row.run_no }}</span>
              <el-tag
                v-if="recentRunIds.has(row.run_id)"
                class="run-list__recent-tag"
                type="success"
                size="small"
                effect="plain"
              >
                刚创建
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="150">
            <template #default="{ row }">
              <el-tag :type="statusTagType(row.status)" size="small">
                {{ statusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="scope_type" label="范围" width="150" />
          <el-table-column label="SKU 总数" width="110" align="right">
            <template #default="{ row }">{{ row.total_skus }}</template>
          </el-table-column>
          <el-table-column label="进度" width="190">
            <template #default="{ row }">
              <el-progress :percentage="row.progress ?? 0" :stroke-width="10" />
            </template>
          </el-table-column>
          <el-table-column label="风险 SKU 数" width="120" align="right">
            <template #default="{ row }">{{ row.risk_sku_count ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="创建时间" min-width="180">
            <!-- 后端列表 DTO 暂未返回 created_at（复用 AnalysisRunStatusResponse），缺失即显示 "-" -->
            <template #default="{ row }">
              {{ row.created_at ? formatDateTime(row.created_at) : '-' }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="110" align="center">
            <template #default="{ row }">
              <el-button link type="primary" @click="openDetail(row)">查看详情</el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-empty v-else-if="!loading" description="暂无分析任务" :image-size="90">
          <el-button type="primary" @click="goCreate">新建分析任务</el-button>
        </el-empty>

        <el-pagination
          v-if="total > 0"
          class="run-list__pager"
          background
          layout="total, sizes, prev, pager, next, jumper"
          :current-page="query.page"
          :page-size="query.pageSize"
          :page-sizes="[10, 20, 50, 100]"
          :total="total"
          @current-change="onPageChange"
          @size-change="onSizeChange"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
// 分析任务列表页（M4-B22.2：接入真实 `GET /analysis/runs`）。
//
// ★ 服务端是唯一数据源：`total_skus` / `progress` / `risk_sku_count` 均为 number，
//   原样展示；即使未来出现字符串数量列也只直显，禁止 `parseFloat()` + `toFixed()`（口径铁律 ④）。
// ★ 轮询（方案 A）：当前页存在 PENDING/RUNNING 行才启动，每轮**静默**刷新当前页
//   （不显示全屏 loading、失败不弹错）；页内无进行中行即停；卸载由 `usePolling` 自动停。
// ★ 「刚创建」只是本机创建的提示标记（`recentRuns` 降级为 UI 线索，不再作数据源）。
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listAnalysisRuns } from '@/api/analysis'
import type { AnalysisRunListItem } from '@/types/analysis'
import { isInFlightStatus } from '@/types/analysis'
import { usePolling } from '@/composables/usePolling'
import { describeError } from '@/utils/error'
import { formatDateTime } from '@/utils/format'
import {
  RUN_SORT_OPTIONS,
  RUN_STATUS_OPTIONS,
  buildAnalysisRunsQuery,
} from './analysisRunQuery'
import { statusLabel, statusTagType } from './analysisStatus'
import { loadRecentRuns } from './recentRuns'

const router = useRouter()

const list = ref<AnalysisRunListItem[]>([])
const total = ref(0)
const loading = ref(false)

const query = reactive({ page: 1, pageSize: 20 })
const filterStatus = ref('')
/** 日期区间（`YYYY-MM-DD`，由 `value-format` 保证）。 */
const dateRange = ref<string[] | null>(null)
const sortValue = ref('')

/** 本机最近创建的 run_id（只用于「刚创建」提示）。 */
const recentRunIds = ref<Set<number>>(new Set())

/** 当前页是否存在进行中（PENDING/RUNNING）任务。 */
function hasInFlight(): boolean {
  return list.value.some((row) => isInFlightStatus(row.status))
}

function currentQuery() {
  return buildAnalysisRunsQuery({
    page: query.page,
    pageSize: query.pageSize,
    status: filterStatus.value,
    dateFrom: dateRange.value?.[0],
    dateTo: dateRange.value?.[1],
    sort: sortValue.value,
  })
}

async function fetchList(options: { silent?: boolean } = {}): Promise<void> {
  const silent = options.silent === true
  if (!silent) loading.value = true
  try {
    const result = await listAnalysisRuns(currentQuery())
    list.value = result.list
    total.value = result.total
    syncPolling()
  } catch (error) {
    // 轮询失败静默：网络抖动不打扰用户，下一轮继续尝试
    if (silent) return
    list.value = []
    total.value = 0
    ElMessage.error(describeError(error))
  } finally {
    if (!silent) loading.value = false
  }
}

const polling = usePolling(() => fetchList({ silent: true }), {
  shouldStop: () => !hasInFlight(),
})

/** 按当前页数据启停轮询：有进行中行才轮询，否则立即停。 */
function syncPolling(): void {
  if (hasInFlight()) {
    if (polling.stopped.value) polling.start()
    return
  }
  polling.stop()
}

function onFilterChange(): void {
  query.page = 1
  void fetchList()
}

function onReset(): void {
  filterStatus.value = ''
  dateRange.value = null
  sortValue.value = ''
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

function goCreate(): void {
  void router.push({ name: 'analysis-run-create' })
}

function openDetail(row: AnalysisRunListItem): void {
  void router.push({ name: 'analysis-run-detail', params: { id: String(row.run_id) } })
}

onMounted(() => {
  recentRunIds.value = new Set(loadRecentRuns().map((entry) => entry.run_id))
  void fetchList()
})
</script>

<style scoped>
.run-list__head {
  display: flex;
  align-items: baseline;
  gap: 12px;
}
.run-list__title {
  font-weight: 600;
}
.run-list__subtitle {
  color: #909399;
  font-size: 12px;
}
.run-list__spacer {
  flex: 1;
}
.run-list__filters {
  margin-bottom: 4px;
}
.run-list__run-no {
  margin-right: 8px;
}
.run-list__pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
