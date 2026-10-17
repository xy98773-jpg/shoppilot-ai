<template>
  <div class="batch-page">
    <el-card shadow="never">
      <template #header>
        <div class="batch-page__head">
          <span class="batch-page__title">导入批次</span>
          <el-button link type="primary" @click="$router.push('/importdata/wizard')">新建导入</el-button>
        </div>
      </template>

      <el-form :inline="true" class="batch-page__filters">
        <el-form-item label="状态">
          <el-select v-model="filter.status" clearable placeholder="全部" style="width: 170px">
            <el-option v-for="s in STATUS_OPTIONS" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="业务类型">
          <el-select v-model="filter.bizType" clearable placeholder="全部" style="width: 180px">
            <el-option v-for="b in BIZ_TYPE_OPTIONS" :key="b" :label="b" :value="b" />
          </el-select>
        </el-form-item>
        <el-form-item label="起始日">
          <el-date-picker
            v-model="filter.from"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="yyyy-MM-dd"
            style="width: 160px"
          />
        </el-form-item>
        <el-form-item label="截止日">
          <el-date-picker
            v-model="filter.to"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="yyyy-MM-dd"
            style="width: 160px"
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
        row-key="batch_id"
        :actions-width="190"
        @page-change="changePage"
        @size-change="changeSize"
      >
        <template #status="{ row }">
          <StatusTag :status="row.status" :label="row.status" />
        </template>
        <template #createdAt="{ row }">{{ formatDateTime(row.created_at) }}</template>
        <template #actions="{ row }">
          <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          <el-button link type="warning" @click="openErrors(row)">错误明细</el-button>
        </template>
      </PageTable>
    </el-card>

    <!-- 详情 -->
    <el-dialog v-model="detailVisible" title="批次详情" width="760px">
      <el-descriptions v-if="detail" :column="2" border size="small">
        <el-descriptions-item label="批次号">{{ detail.batch_no }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ detail.status }}</el-descriptions-item>
        <el-descriptions-item label="业务类型">{{ detail.biz_type }}</el-descriptions-item>
        <el-descriptions-item label="文件">{{ detail.file_name }}</el-descriptions-item>
        <el-descriptions-item label="总行数">{{ detail.total_rows }}</el-descriptions-item>
        <el-descriptions-item label="成功 / 失败">{{ detail.success_rows }} / {{ detail.failed_rows }}</el-descriptions-item>
        <el-descriptions-item label="错误行">{{ detail.error_rows }}</el-descriptions-item>
        <el-descriptions-item label="警告行">{{ detail.warning_rows }}</el-descriptions-item>
        <el-descriptions-item label="错误码">{{ detail.error_code || '-' }}</el-descriptions-item>
        <el-descriptions-item label="错误信息">{{ detail.error_message || '-' }}</el-descriptions-item>
        <el-descriptions-item label="开始时间">{{ formatDateTime(detail.started_at ?? '') }}</el-descriptions-item>
        <el-descriptions-item label="结束时间">{{ formatDateTime(detail.finished_at ?? '') }}</el-descriptions-item>
        <el-descriptions-item label="尝试次数">{{ detail.attempt_no }}</el-descriptions-item>
        <el-descriptions-item label="模板版本">{{ detail.template_version || '-' }}</el-descriptions-item>
      </el-descriptions>
    </el-dialog>

    <!-- 错误明细 -->
    <el-dialog v-model="errorVisible" title="错误 / 警告明细" width="900px">
      <el-form :inline="true" class="batch-page__filters">
        <el-form-item label="级别">
          <el-select v-model="errorLevel" clearable placeholder="全部" style="width: 140px">
            <el-option label="ERROR" value="ERROR" />
            <el-option label="WARNING" value="WARNING" />
          </el-select>
        </el-form-item>
        <el-form-item label="行号">
          <el-input v-model="errorRowNo" placeholder="如 45" style="width: 120px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="reloadErrors(1)">筛选</el-button>
          <el-button :loading="exporting" @click="onExport">导出 CSV</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="errorList" border size="small">
        <el-table-column prop="row_no" label="行号" width="90" />
        <el-table-column prop="error_level" label="级别" width="100" />
        <el-table-column prop="error_code" label="错误码" width="120" />
        <el-table-column prop="error_field" label="字段" width="140" />
        <el-table-column prop="error_message" label="说明" min-width="240" />
      </el-table>

      <el-pagination
        class="batch-page__pager"
        :current-page="errorQuery.page"
        :page-size="errorQuery.pageSize"
        :total="errorTotal"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        background
        @current-change="reloadErrors"
        @size-change="onErrorSizeChange"
      />
      <p class="batch-page__note">
        导出使用与当前筛选一致的参数（`errorLevel` / `rowNo`），导出结果即「当前筛选结果」。
      </p>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
// 导入批次列表（F-IMP-04）+ 详情 + 错误分页 + 错误导出。
//
// ★ 只展示后端返回的状态与计数；不在前端推断「是否可确认」「是否成功」
//   （状态机语义见 `IMPORT_WORKFLOW §13`，判定在服务端）。
// ★ 导出走 axios Blob 而非 `<a href>`：CSV 端点需要 Authorization 头，直链不会携带。
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageTable from '@/components/common/PageTable.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import {
  exportBatchErrors,
  getBatch,
  listBatchErrors,
  listBatches,
} from '@/api/importdata'
import type {
  ImportBatchDetail,
  ImportBatchListItem,
  ImportErrorRow,
} from '@/types/importdata'
import type { TableColumn } from '@/types/ui'
import { usePagination } from '@/composables/usePagination'
import { describeError } from '@/utils/error'
import { formatDateTime } from '@/utils/format'
import {
  buildBatchQuery,
  buildErrorExportParams,
  buildErrorQuery,
  type BatchFilterInput,
} from './batchQuery'

const STATUS_OPTIONS = [
  'UPLOADED',
  'PARSED',
  'MAPPED',
  'VALIDATED',
  'IMPORTING',
  'COMPLETED',
  'PARTIAL_SUCCESS',
  'FAILED',
  'CANCELLED',
  'DUPLICATE_REJECTED',
]

const BIZ_TYPE_OPTIONS = [
  'PRODUCT',
  'SKU',
  'SUPPLIER',
  'WAREHOUSE',
  'INVENTORY_SNAPSHOT',
  'SALES_DAILY',
]

const columns: TableColumn[] = [
  { prop: 'batch_no', label: '批次号', minWidth: 170 },
  { prop: 'biz_type', label: '业务类型', width: 170 },
  { prop: 'file_name', label: '文件', minWidth: 180 },
  { prop: 'status', label: '状态', width: 150, slot: 'status' },
  { prop: 'total_rows', label: '总行数', width: 90 },
  { prop: 'success_rows', label: '成功', width: 80 },
  { prop: 'failed_rows', label: '失败', width: 80 },
  { prop: 'created_at', label: '创建时间', minWidth: 170, slot: 'createdAt' },
]

const filter = reactive<BatchFilterInput>({ status: '', bizType: '', from: '', to: '' })

const { list, total, loading, query, load, changePage, changeSize } = usePagination<ImportBatchListItem>(
  (q) => listBatches(buildBatchQuery(filter, q.page, q.pageSize)),
)

function onSearch(): void {
  query.page = 1
  void load()
}

function onReset(): void {
  filter.status = ''
  filter.bizType = ''
  filter.from = ''
  filter.to = ''
  query.page = 1
  void load()
}

// ---------------------------------------------------------------- 详情
const detailVisible = ref(false)
const detail = ref<ImportBatchDetail | null>(null)

async function openDetail(row: ImportBatchListItem): Promise<void> {
  try {
    detail.value = await getBatch(row.batch_id)
    detailVisible.value = true
  } catch (error) {
    ElMessage.error(describeError(error))
  }
}

// ---------------------------------------------------------------- 错误明细
const errorVisible = ref(false)
const errorList = ref<ImportErrorRow[]>([])
const errorTotal = ref(0)
const errorBatchId = ref<number | null>(null)
const errorLevel = ref('')
const errorRowNo = ref('')
const errorQuery = reactive({ page: 1, pageSize: 20 })
const exporting = ref(false)

async function openErrors(row: ImportBatchListItem): Promise<void> {
  errorBatchId.value = row.batch_id
  errorLevel.value = ''
  errorRowNo.value = ''
  errorVisible.value = true
  await reloadErrors(1)
}

async function reloadErrors(page = errorQuery.page): Promise<void> {
  if (errorBatchId.value === null) {
    return
  }
  errorQuery.page = page
  try {
    const result = await listBatchErrors(
      errorBatchId.value,
      buildErrorQuery(errorQuery.page, errorQuery.pageSize, errorLevel.value, errorRowNo.value),
    )
    errorList.value = result.list
    errorTotal.value = result.total
  } catch (error) {
    ElMessage.error(describeError(error))
  }
}

function onErrorSizeChange(size: number): void {
  errorQuery.pageSize = size
  void reloadErrors(1)
}

/** 导出当前筛选结果（CSV，UTF-8 BOM 由服务端写入）。 */
async function onExport(): Promise<void> {
  if (errorBatchId.value === null) {
    return
  }
  exporting.value = true
  try {
    const blob = await exportBatchErrors(
      errorBatchId.value,
      buildErrorExportParams(errorLevel.value, errorRowNo.value),
    )
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `import-errors-${errorBatchId.value}.csv`
    link.click()
    URL.revokeObjectURL(url)
    ElMessage.success('已导出当前筛选结果')
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    exporting.value = false
  }
}

onMounted(() => {
  void load()
})
</script>

<style scoped>
.batch-page__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.batch-page__title {
  font-weight: 600;
}
.batch-page__filters {
  margin-bottom: 4px;
}
.batch-page__pager {
  margin-top: 12px;
  justify-content: flex-end;
}
.batch-page__note {
  margin: 8px 0 0;
  color: #909399;
  font-size: 12px;
}
</style>
