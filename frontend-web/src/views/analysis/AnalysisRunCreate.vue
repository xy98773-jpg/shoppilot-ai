<template>
  <div class="run-create">
    <el-card shadow="never">
      <template #header>
        <div class="run-create__head">
          <span class="run-create__title">新建分析任务</span>
          <span class="run-create__subtitle">
            幂等键由前端生成并在本次表单会话内固定（失败重试复用同一键，不产生重复任务）
          </span>
          <span class="run-create__spacer" />
          <el-button size="small" @click="goList">返回列表</el-button>
        </div>
      </template>

      <el-form label-width="150px" class="run-create__form">
        <el-divider content-position="left">分析范围</el-divider>

        <el-form-item label="范围类型" required>
          <el-select v-model="form.scope_type" style="width: 220px">
            <el-option label="全部活跃 SKU（ALL_ACTIVE）" value="ALL_ACTIVE" />
            <el-option label="指定 SKU（SELECTED_SKUS）" value="SELECTED_SKUS" />
            <el-option label="按类目（CATEGORY）" value="CATEGORY" />
          </el-select>
        </el-form-item>

        <el-form-item v-if="form.scope_type === 'SELECTED_SKUS'" label="SKU ID" required>
          <el-input
            v-model="form.sku_ids"
            type="textarea"
            :rows="3"
            placeholder="多个 SKU ID 用逗号/空格分隔，如 5001, 5002（上限 2000 个）"
          />
        </el-form-item>

        <el-form-item v-if="form.scope_type === 'CATEGORY'" label="分析类目" required>
          <el-input v-model="form.category" placeholder="如 箱包" style="width: 260px" />
        </el-form-item>

        <el-form-item label="限定仓库 ID">
          <el-input v-model="form.warehouse_id" placeholder="留空 = 默认仓" style="width: 200px" />
        </el-form-item>

        <el-form-item label="数据窗口" required>
          <el-date-picker
            v-model="form.date_from"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="起始日"
            style="width: 155px"
          />
          <span class="run-create__dash">~</span>
          <el-date-picker
            v-model="form.date_to"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="截止日"
            style="width: 155px"
          />
          <span class="run-create__hint">窗口内有效销售天数不足会返回 ANL-7008（数据不足）</span>
        </el-form-item>

        <el-divider content-position="left">运行参数（缺省取服务端默认值）</el-divider>

        <el-form-item label="统计窗口 window_days">
          <el-input v-model="form.window_days" style="width: 120px" />
          <span class="run-create__hint">1..365，默认 28</span>
        </el-form-item>
        <el-form-item label="最少有效天数 min_days">
          <el-input v-model="form.min_days" style="width: 120px" />
          <span class="run-create__hint">1..统计窗口，默认 7</span>
        </el-form-item>
        <el-form-item label="覆盖天数 coverage_days">
          <el-input v-model="form.coverage_days" style="width: 120px" />
          <span class="run-create__hint">0..365，默认 7</span>
        </el-form-item>
        <el-form-item label="默认交期 lead_time_default">
          <el-input v-model="form.lead_time_default" style="width: 120px" />
          <span class="run-create__hint">0..365 天，默认 7</span>
        </el-form-item>
        <el-form-item label="安全库存系数 ss_ratio">
          <el-input v-model="form.ss_ratio" style="width: 120px" />
          <span class="run-create__hint">非负，默认 0.5</span>
        </el-form-item>
        <el-form-item label="CV 阈值 cv_high">
          <el-input v-model="form.cv_high" style="width: 120px" />
          <span class="run-create__hint">非负，默认 1.0</span>
        </el-form-item>
        <el-form-item label="预算上限 budget_limit">
          <el-input v-model="form.budget_limit" placeholder="留空 = 不限制" style="width: 160px" />
        </el-form-item>

        <el-form-item label="启用 LLM 解释">
          <el-switch v-model="form.enable_llm" />
        </el-form-item>

        <el-divider content-position="left">算法与模型</el-divider>

        <el-form-item label="算法版本" required>
          <el-input v-model="form.algorithm_version" style="width: 260px" />
        </el-form-item>
        <el-form-item v-if="form.enable_llm" label="Prompt 版本" required>
          <el-input v-model="form.prompt_version" style="width: 260px" />
        </el-form-item>
        <el-form-item v-if="form.enable_llm" label="模型名">
          <el-input v-model="form.model_name" placeholder="留空 = 服务端默认模型" style="width: 260px" />
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="submitting" @click="onSubmit">创建并开始分析</el-button>
          <el-button :disabled="submitting" @click="onReset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
// 新建分析任务（M4-T16）。
//
// ★ 幂等：`createIdempotencyIntent()` 在本次表单会话内固定一个键；
//   失败重试复用同一键（服务端按同键同体重放），成功后才 reset。
// ★ `team_id` 不传（服务端从 JWT 解析）。
// ★ 成功（含幂等重放）后记录到本地「最近创建」并跳转详情页轮询进度。
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createAnalysisRun } from '@/api/analysis'
import { createIdempotencyIntent } from '@/utils/idempotency'
import { describeError } from '@/utils/error'
import { classifyAnalysisError } from './analysisError'
import { buildCreatePayload, createDefaultForm, validateAnalysisForm } from './analysisForm'
import { rememberRun } from './recentRuns'

const router = useRouter()

const form = reactive(createDefaultForm())
const submitting = ref(false)
const intent = createIdempotencyIntent()

async function onSubmit(): Promise<void> {
  const invalid = validateAnalysisForm(form)
  if (invalid) {
    ElMessage.warning(invalid)
    return
  }
  submitting.value = true
  try {
    const result = await createAnalysisRun(buildCreatePayload(form), intent.key())
    if (result.replayed) {
      ElMessage.warning('该分析任务已提交过（幂等重放），已打开首次创建的任务')
    } else {
      ElMessage.success(`已创建分析任务 ${result.data.run_no}`)
    }
    rememberRun({
      run_id: result.data.run_id,
      run_no: result.data.run_no,
      status: result.data.status,
      created_at: result.data.created_at,
    })
    intent.reset()
    void router.push({
      name: 'analysis-run-detail',
      params: { id: String(result.data.run_id) },
    })
  } catch (error) {
    // 失败不重置幂等键：重试必须复用同一键
    const hint = classifyAnalysisError(error)
    if (hint.action === 'data-insufficient') {
      ElMessage.error(`${hint.message}（${describeError(error)}）`)
    } else {
      ElMessage.error(describeError(error))
    }
  } finally {
    submitting.value = false
  }
}

function onReset(): void {
  Object.assign(form, createDefaultForm())
  intent.reset()
}

function goList(): void {
  void router.push({ name: 'analysis-runs' })
}
</script>

<style scoped>
.run-create__head {
  display: flex;
  align-items: baseline;
  gap: 12px;
}
.run-create__title {
  font-weight: 600;
}
.run-create__subtitle {
  color: #909399;
  font-size: 12px;
}
.run-create__spacer {
  flex: 1;
}
.run-create__form {
  max-width: 980px;
}
.run-create__dash {
  margin: 0 8px;
  color: #909399;
}
.run-create__hint {
  margin-left: 10px;
  color: #909399;
  font-size: 12px;
}
</style>
