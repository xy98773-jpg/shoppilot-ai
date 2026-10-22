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

      <el-alert
        type="info"
        :closable="false"
        show-icon
        class="run-create__hint-bar"
        title="字段格式：中文含义（英文参数名）。括号内英文是接口参数名，填写时按中文提示的值填即可。"
      />

      <el-form label-width="210px" class="run-create__form">
        <el-divider content-position="left">分析范围（要分析哪些商品）</el-divider>

        <el-form-item label="范围类型（scope_type）" required>
          <el-select v-model="form.scope_type" style="width: 260px">
            <el-option label="全部活跃 SKU（ALL_ACTIVE）" value="ALL_ACTIVE" />
            <el-option label="指定 SKU（SELECTED_SKUS）" value="SELECTED_SKUS" />
            <el-option label="按类目（CATEGORY）" value="CATEGORY" />
          </el-select>
        </el-form-item>

        <el-form-item v-if="form.scope_type === 'SELECTED_SKUS'" label="SKU ID（sku_ids）" required>
          <el-input
            v-model="form.sku_ids"
            type="textarea"
            :rows="3"
            placeholder="多个 SKU ID 用逗号/空格分隔，如 910001, 910002（上限 2000 个）"
          />
        </el-form-item>

        <el-form-item v-if="form.scope_type === 'CATEGORY'" label="分析类目（category）" required>
          <el-input v-model="form.category" placeholder="如 箱包" style="width: 260px" />
        </el-form-item>

        <el-form-item label="限定仓库 ID（warehouse_id）">
          <el-input v-model="form.warehouse_id" placeholder="留空 = 默认仓" style="width: 200px" />
          <span class="run-create__hint">只统计该仓库存；留空取团队默认仓</span>
        </el-form-item>

        <el-form-item label="数据窗口（date_from ~ date_to）" required>
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

        <el-divider content-position="left">运行参数（怎么算，缺省取服务端默认值）</el-divider>

        <el-form-item label="统计窗口（window_days）">
          <el-input v-model="form.window_days" style="width: 120px" />
          <span class="run-create__hint">1..365，默认 28；用最近多少天的销量算日均销量 D</span>
        </el-form-item>
        <el-form-item label="最少有效天数（min_days）">
          <el-input v-model="form.min_days" style="width: 120px" />
          <span class="run-create__hint">1..统计窗口，默认 7；有效天数不足则判 DATA_INSUFFICIENT（数据不足）</span>
        </el-form-item>
        <el-form-item label="覆盖天数（coverage_days，补货周期 R）">
          <el-input v-model="form.coverage_days" style="width: 120px" />
          <span class="run-create__hint">0..365，默认 7；这次补的货要覆盖几天用量</span>
        </el-form-item>
        <el-form-item label="默认交期（lead_time_default，交期 L）">
          <el-input v-model="form.lead_time_default" style="width: 120px" />
          <span class="run-create__hint">0..365 天，默认 7；下单到到货的天数（SKU 自身配置优先）</span>
        </el-form-item>
        <el-form-item label="安全库存系数（ss_ratio，SS 系数）">
          <el-input v-model="form.ss_ratio" style="width: 120px" />
          <span class="run-create__hint">非负，默认 0.5；安全库存 SS = 日均销量 × ceil(交期 × 系数)</span>
        </el-form-item>
        <el-form-item label="CV 阈值（cv_high，销量波动系数）">
          <el-input v-model="form.cv_high" style="width: 120px" />
          <span class="run-create__hint">非负，默认 1.0；CV 超过它则安全库存 ×1.5（波动大要多备）</span>
        </el-form-item>
        <el-form-item label="预算上限（budget_limit）">
          <el-input v-model="form.budget_limit" placeholder="留空 = 不限制" style="width: 160px" />
          <span class="run-create__hint">超出预算的补货建议会被截断</span>
        </el-form-item>

        <el-form-item label="启用 LLM 解释（enable_llm）">
          <el-switch v-model="form.enable_llm" />
          <span class="run-create__hint">关闭则只有数字结论，没有大模型写的中文解读</span>
        </el-form-item>

        <el-divider content-position="left">算法与模型（用哪个算法、哪个大模型）</el-divider>

        <el-form-item label="算法版本（algorithm_version）" required>
          <el-input v-model="form.algorithm_version" style="width: 260px" />
          <span class="run-create__hint">默认 avg-ma-3sigma-v1（移动平均 + 3σ 截尾）</span>
        </el-form-item>
        <el-form-item v-if="form.enable_llm" label="Prompt 版本（prompt_version）" required>
          <el-input v-model="form.prompt_version" style="width: 260px" />
          <span class="run-create__hint">默认 explain_risk.v1（风险解释提示词模板）</span>
        </el-form-item>
        <el-form-item v-if="form.enable_llm" label="模型名（model_name）">
          <el-input v-model="form.model_name" placeholder="留空 = 服务端默认模型" style="width: 260px" />
          <span class="run-create__hint">留空用服务端默认（当前 DeepSeek deepseek-chat）</span>
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
.run-create__hint-bar {
  margin-bottom: 12px;
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
