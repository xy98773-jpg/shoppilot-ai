<template>
  <div class="import-wizard">
    <el-card shadow="never">
      <template #header>
        <div class="import-wizard__head">
          <span class="import-wizard__title">导入向导</span>
          <el-button link type="primary" @click="onReset">重新开始</el-button>
        </div>
      </template>

      <el-steps :active="step" finish-status="success" align-center class="import-wizard__steps">
        <el-step title="上传" />
        <el-step title="预览" :description="previewDesc" />
        <el-step title="映射" :description="mappingDesc" />
        <el-step title="校验" :description="validateDesc" />
        <el-step title="确认" />
      </el-steps>

      <el-alert
        v-if="errorMessage"
        :title="errorMessage"
        type="error"
        show-icon
        :closable="false"
        class="import-wizard__alert"
      />

      <!-- 步骤 0：上传 -->
      <section v-if="step === 0" class="import-wizard__section">
        <el-form label-width="120px">
          <el-form-item label="业务类型" required>
            <el-select v-model="bizType" style="width: 260px" placeholder="选择导入业务类型">
              <el-option
                v-for="tpl in templates"
                :key="tpl.biz_type"
                :label="`${tpl.biz_type}（${tpl.template_version}）`"
                :value="tpl.biz_type"
              />
            </el-select>
            <span class="import-wizard__hint">仅支持 CSV / Excel，单文件 ≤ 20MB</span>
          </el-form-item>
          <el-form-item label="模板版本">
            <el-input v-model="templateVersion" placeholder="可选，留空用服务端默认" style="width: 260px" />
          </el-form-item>
          <el-form-item label="选择文件" required>
            <el-upload
              :auto-upload="false"
              :limit="1"
              :on-change="onFileChange"
              :on-remove="onFileRemove"
              accept=".csv,.xlsx,.xls"
            >
              <el-button>选择文件</el-button>
            </el-upload>
            <span v-if="selectedFile" class="import-wizard__hint">
              已选：{{ selectedFile.name }}（{{ selectedFile.size }} 字节）
            </span>
          </el-form-item>
          <el-form-item>
            <el-button
              type="primary"
              :loading="busy"
              :disabled="!selectedFile || !bizType"
              @click="onUpload"
            >
              上传并预览
            </el-button>
          </el-form-item>
        </el-form>
      </section>

      <!-- 步骤 1：预览 -->
      <section v-else-if="step === 1 && preview" class="import-wizard__section">
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="批次">{{ batch?.batch_no }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ preview.status }}</el-descriptions-item>
          <el-descriptions-item label="数据行">{{ preview.total_rows }}</el-descriptions-item>
          <el-descriptions-item label="工作表">
            {{ preview.sheet_name || '（CSV 无工作表）' }}
          </el-descriptions-item>
          <el-descriptions-item label="编码">{{ preview.encoding || '-' }}</el-descriptions-item>
          <el-descriptions-item label="预览行数">{{ preview.preview_rows }}</el-descriptions-item>
        </el-descriptions>

        <el-table :data="preview.preview" border size="small" class="import-wizard__table">
          <el-table-column
            v-for="col in preview.columns"
            :key="col.source_name"
            :prop="col.source_name"
            :label="col.mapped_field ? `${col.source_name} → ${col.mapped_field}` : col.source_name"
            min-width="140"
          />
        </el-table>

        <div class="import-wizard__actions">
          <el-button type="primary" @click="goMapping()">下一步：字段映射</el-button>
        </div>
      </section>

      <!-- 步骤 2：映射 -->
      <section v-else-if="step === 2 && preview" class="import-wizard__section">
        <p class="import-wizard__note">
          左侧为目标字段（模板定义），右侧选择对应的源列；未映射的非必填字段可留空。
        </p>
        <el-table :data="mappingRows" border size="small" class="import-wizard__table">
          <el-table-column prop="label" label="目标字段" min-width="200" />
          <el-table-column label="必填" width="80">
            <template #default="scope">{{ scope.row.required ? '是' : '否' }}</template>
          </el-table-column>
          <el-table-column label="源列" min-width="220">
            <template #default="scope">
              <el-select
                v-model="mapping[scope.row.name]"
                clearable
                placeholder="选择源列"
                style="width: 100%"
              >
                <el-option v-for="col in sourceColumns" :key="col" :label="col" :value="col" />
              </el-select>
            </template>
          </el-table-column>
        </el-table>

        <div class="import-wizard__actions">
          <el-button @click="step = 1">上一步</el-button>
          <el-button type="primary" :loading="busy" @click="onSubmitMapping">提交映射</el-button>
        </div>
      </section>

      <!-- 步骤 3：校验 -->
      <section v-else-if="step === 3" class="import-wizard__section">
        <p class="import-wizard__note">
          映射已提交（状态以服务端返回为准）。点击「开始校验」执行全量校验；
          全部失败会返回 422 IMP-4010。
        </p>
        <div class="import-wizard__actions">
          <el-button type="primary" :loading="busy" @click="onValidate">开始校验</el-button>
        </div>
      </section>

      <!-- 步骤 4：确认 -->
      <section v-else-if="step === 4" class="import-wizard__section">
        <el-descriptions v-if="validation" :column="3" border size="small">
          <el-descriptions-item label="总行数">{{ validation.total_rows }}</el-descriptions-item>
          <el-descriptions-item label="错误行">{{ validation.error_rows }}</el-descriptions-item>
          <el-descriptions-item label="警告行">{{ validation.warning_rows }}</el-descriptions-item>
        </el-descriptions>

        <el-alert
          v-if="validation && validation.error_rows > 0"
          title="存在错误行，确认落库会被服务端拒绝（422 IMP-4009）；请先修正数据或走强制重导流程。"
          type="warning"
          show-icon
          :closable="false"
          class="import-wizard__alert"
        />

        <el-table
          v-if="validation && validation.errors.length > 0"
          :data="validation.errors"
          border
          size="small"
          class="import-wizard__table"
        >
          <el-table-column prop="row_no" label="行号" width="90" />
          <el-table-column prop="error_level" label="级别" width="100" />
          <el-table-column prop="error_code" label="错误码" width="120" />
          <el-table-column prop="error_field" label="字段" width="140" />
          <el-table-column prop="error_message" label="说明" min-width="240" />
        </el-table>

        <div class="import-wizard__actions">
          <el-button
            type="primary"
            :loading="busy"
            :disabled="!!confirmResult"
            @click="onConfirm"
          >
            确认落库
          </el-button>
          <el-button v-if="confirmResult" @click="onReset">结束本批次</el-button>
        </div>

        <el-descriptions v-if="confirmResult" :column="2" border size="small" class="import-wizard__result">
          <el-descriptions-item label="确认结果">{{ confirmResult.status }}</el-descriptions-item>
          <el-descriptions-item label="成功行">{{ confirmResult.success_rows }}</el-descriptions-item>
          <el-descriptions-item label="失败行">{{ confirmResult.failed_rows }}</el-descriptions-item>
          <el-descriptions-item label="幂等重放">
            {{ replayed ? '是（该操作已提交过，未重复落库）' : '否（本次新执行）' }}
          </el-descriptions-item>
        </el-descriptions>
      </section>
    </el-card>
  </div>
</template>

<script setup lang="ts">
// 导入向导（F-IMP-01/03）：上传 → 预览 → 映射 → 校验 → 确认。
//
// ★ 口径（PROJECT_STRUCTURE §6.1）：本页不判断状态机迁移、不判断能否确认
//   （「存在 ERROR 行则拒绝」由服务端 422 IMP-4009 决定，页面只展示提示）。
// ★ 确认落库使用一次意图一个幂等键（重试复用），响应头 `Idempotent-Replayed` 由
//   `api/http.ts` 的探测能力带回，命中即提示「已提交过」。
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { UploadFile } from 'element-plus'
import { listTemplates } from '@/api/importdata'
import type { ImportTemplate } from '@/types/importdata'
import { describeError } from '@/utils/error'
import { createIdempotencyIntent } from '@/utils/idempotency'
import { useImportWizard } from './useImportWizard'

const {
  step,
  busy,
  errorMessage,
  batch,
  preview,
  mapping,
  validation,
  confirmResult,
  replayed,
  upload,
  goMapping,
  submitMapping,
  runValidate,
  confirm,
  reset,
} = useImportWizard()

const templates = ref<ImportTemplate[]>([])
const bizType = ref<string>('')
const templateVersion = ref('')
const selectedFile = ref<File | null>(null)

/** 确认落库的幂等键容器：成功后重置，失败重试复用同一键。 */
const confirmIntent = createIdempotencyIntent()

const currentTemplate = computed(() => templates.value.find((t) => t.biz_type === bizType.value))
const mappingRows = computed(() => currentTemplate.value?.fields ?? [])
const sourceColumns = computed(() => (preview.value?.columns ?? []).map((c) => c.source_name))

const previewDesc = computed(() => (preview.value ? `预览 ${preview.value.preview_rows} 行` : ''))
const mappingDesc = computed(() => {
  const filled = Object.keys(mapping).filter((k) => !!mapping[k]).length
  return filled > 0 ? `已选 ${filled} 个字段` : ''
})
const validateDesc = computed(() =>
  validation.value ? `错误 ${validation.value.error_rows} / 警告 ${validation.value.warning_rows}` : '',
)

function onFileChange(file: UploadFile): void {
  selectedFile.value = file.raw ?? null
}

function onFileRemove(): void {
  selectedFile.value = null
}

async function onUpload(): Promise<void> {
  if (!selectedFile.value || !bizType.value) {
    return
  }
  try {
    await upload(selectedFile.value, bizType.value, templateVersion.value.trim() || undefined)
    ElMessage.success('上传成功，已生成预览')
  } catch (error) {
    ElMessage.error(describeError(error))
  }
}

async function onSubmitMapping(): Promise<void> {
  try {
    await submitMapping()
    ElMessage.success('映射已提交')
  } catch (error) {
    ElMessage.error(describeError(error))
  }
}

async function onValidate(): Promise<void> {
  try {
    await runValidate()
    ElMessage.success('校验完成')
  } catch (error) {
    ElMessage.error(describeError(error))
  }
}

async function onConfirm(): Promise<void> {
  try {
    const result = await confirm(confirmIntent.key())
    if (result.replayed) {
      ElMessage.warning('该操作已提交过，服务端返回首次结果，未重复落库')
    } else {
      ElMessage.success(`已确认：${result.data.status}`)
      confirmIntent.reset()
    }
  } catch (error) {
    ElMessage.error(describeError(error))
    // 失败不重置键：用户重试同一意图必须复用同一 Idempotency-Key
  }
}

function onReset(): void {
  reset()
  selectedFile.value = null
  confirmIntent.reset()
}

onMounted(async () => {
  try {
    templates.value = await listTemplates()
    const first = templates.value[0]
    if (first) {
      bizType.value = first.biz_type
    }
  } catch (error) {
    ElMessage.error(describeError(error))
  }
})
</script>

<style scoped>
.import-wizard__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.import-wizard__title {
  font-weight: 600;
}
.import-wizard__steps {
  margin-bottom: 20px;
}
.import-wizard__section {
  margin-top: 8px;
}
.import-wizard__table {
  margin-top: 16px;
  width: 100%;
}
.import-wizard__actions {
  margin-top: 16px;
  display: flex;
  gap: 8px;
}
.import-wizard__alert {
  margin-top: 12px;
}
.import-wizard__hint {
  margin-left: 12px;
  color: #909399;
  font-size: 12px;
}
.import-wizard__note {
  margin: 0 0 8px;
  color: #606266;
  font-size: 13px;
  line-height: 1.6;
}
.import-wizard__result {
  margin-top: 16px;
}
</style>
