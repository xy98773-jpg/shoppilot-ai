<template>
  <div class="run-list">
    <el-card shadow="never">
      <template #header>
        <div class="run-list__head">
          <span class="run-list__title">分析任务</span>
          <span class="run-list__subtitle">
            任务列表 API（GET /analysis/runs）尚未上线（B22）；本页只展示本机最近创建，可按 run_id 直接打开
          </span>
          <span class="run-list__spacer" />
          <el-button type="primary" @click="goCreate">新建分析任务</el-button>
        </div>
      </template>

      <el-form :inline="true" class="run-list__open">
        <el-form-item label="按 run_id 打开">
          <el-input
            v-model="runIdInput"
            placeholder="如 30001"
            style="width: 180px"
            @keyup.enter="openById"
          />
        </el-form-item>
        <el-form-item>
          <el-button :disabled="!runIdInput.trim()" @click="openById">打开</el-button>
        </el-form-item>
      </el-form>

      <template v-if="recent.length > 0">
        <div class="run-list__section">
          <span class="run-list__section-title">本机最近创建</span>
          <el-button link type="info" @click="onClear">清空</el-button>
        </div>
        <el-table :data="recent" border stripe size="default">
          <el-table-column prop="run_no" label="运行编号" min-width="180" show-overflow-tooltip />
          <el-table-column prop="run_id" label="运行 ID" width="110" />
          <el-table-column label="状态" width="150">
            <template #default="{ row }">
              <el-tag :type="statusTagType(row.status)" size="small">
                {{ statusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" min-width="180">
            <template #default="{ row }">{{ formatDateTime(row.created_at) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="100" align="center">
            <template #default="{ row }">
              <el-button link type="primary" @click="openRun(row.run_id)">查看进度</el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>

      <el-empty v-else description="本机还没有创建记录" :image-size="90">
        <el-button type="primary" @click="goCreate">新建分析任务</el-button>
      </el-empty>
    </el-card>
  </div>
</template>

<script setup lang="ts">
// 分析任务列表（M4-T16）。
//
// ★ 口径（项目负责人裁定 1）：`GET /analysis/runs` 属 B22，本批**禁止调用不存在的列表接口**。
//   本页只提供三件事：新建入口 / 直接输入 run_id 打开 / 本地「最近创建」快捷入口。
// ★ 本地记录只是导航便利，状态以详情页 `GET /analysis/runs/{id}` 为准。
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { formatDateTime } from '@/utils/format'
import { statusLabel, statusTagType } from './analysisStatus'
import { clearRecentRuns, loadRecentRuns, type RecentRunEntry } from './recentRuns'

const router = useRouter()
const runIdInput = ref('')
const recent = ref<RecentRunEntry[]>([])

onMounted(() => {
  recent.value = loadRecentRuns()
})

function goCreate(): void {
  void router.push({ name: 'analysis-run-create' })
}

function openRun(runId: number | string): void {
  void router.push({ name: 'analysis-run-detail', params: { id: String(runId) } })
}

function openById(): void {
  const raw = runIdInput.value.trim()
  if (!raw) return
  if (!/^\d+$/.test(raw)) {
    ElMessage.warning('run_id 必须是数字')
    return
  }
  openRun(raw)
}

async function onClear(): Promise<void> {
  try {
    await ElMessageBox.confirm('清空本机「最近创建」记录？不影响服务端任务。', '清空本地记录', {
      type: 'warning',
    })
  } catch {
    return
  }
  clearRecentRuns()
  recent.value = []
}
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
.run-list__open {
  margin-bottom: 4px;
}
.run-list__section {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 8px 0;
}
.run-list__section-title {
  font-weight: 600;
  font-size: 13px;
}
</style>
