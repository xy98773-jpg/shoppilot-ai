// 导入向导的状态机（`M3-B10` / T18-4）。
//
// 抽成 composable 的原因：
//   - `<script setup>` 内不能 export，而「步骤推进 + 各步错误数」必须能被单测直接驱动；
//   - 组件只负责渲染，状态迁移集中在一处，避免模板里堆 `v-if` 判断。
//
// ★ 状态迁移只认服务端返回的 `status`：本模块**不**自行推断
//   「UPLOADED → PARSED → MAPPED → VALIDATED」是否合法（那是后端状态机，非法会 422 IMP-4005）。
import { computed, reactive, ref } from 'vue'
import {
  confirmBatch,
  previewBatch,
  putMapping,
  uploadBatch,
  validateBatch,
} from '@/api/importdata'
import type {
  ConfirmResult,
  ImportBatchUpload,
  ImportPreview,
  ValidationResult,
} from '@/types/importdata'
import type { ReplayableResult } from '@/api/http'

/** 步骤标题（顺序即 `IMPORT_WORKFLOW §13` 的前 5 步）。 */
export const WIZARD_STEPS = ['上传', '预览', '映射', '校验', '确认'] as const

export function useImportWizard() {
  /** 当前步骤（0 起）。 */
  const step = ref(0)
  /** 请求进行中（按钮 loading）。 */
  const busy = ref(false)
  /** 面向用户的一行错误提示（来自服务端 code/message，不硬编码业务文案）。 */
  const errorMessage = ref('')

  const batch = ref<ImportBatchUpload | null>(null)
  const preview = ref<ImportPreview | null>(null)
  /** 字段映射：目标字段名 → 源列名（`reactive`，供表单双向绑定）。 */
  const mapping = reactive<Record<string, string>>({})
  const validation = ref<ValidationResult | null>(null)
  const confirmResult = ref<ConfirmResult | null>(null)
  /** 最近一次写请求是否命中服务端幂等重放（`Idempotent-Replayed: true`）。 */
  const replayed = ref(false)

  const batchId = computed(() => batch.value?.batch_id ?? null)
  const stepTitle = computed(() => WIZARD_STEPS[step.value])
  /** 各步错误数（步骤条描述用；未到达的步骤为 `null`）。 */
  const errorCounts = computed(() => ({
    previewRows: preview.value ? preview.value.preview_rows : null,
    totalRows: validation.value ? validation.value.total_rows : preview.value?.total_rows ?? null,
    errorRows: validation.value ? validation.value.error_rows : null,
    warningRows: validation.value ? validation.value.warning_rows : null,
  }))

  /** 上传文件并进入预览（步骤 0 → 1）。 */
  async function upload(file: File, bizType: string, templateVersion?: string): Promise<void> {
    busy.value = true
    errorMessage.value = ''
    try {
      batch.value = await uploadBatch(file, bizType, templateVersion)
      await loadPreview()
    } catch (error) {
      errorMessage.value = messageOf(error)
      throw error
    } finally {
      busy.value = false
    }
  }

  /** 拉预览（幂等；可重复调用）。成功后进入步骤 1。 */
  async function loadPreview(limit?: number): Promise<void> {
    if (!batch.value) {
      return
    }
    preview.value = await previewBatch(batch.value.batch_id, limit)
    // 自动映射建议直接填入表单，用户可改（提交时仍以用户提交的映射为准）
    for (const key of Object.keys(mapping)) {
      delete mapping[key]
    }
    Object.assign(mapping, preview.value.suggested_mapping)
    step.value = 1
  }

  /** 从预览进入映射步骤（纯前端翻页，不产生请求）。 */
  function goMapping(): void {
    if (preview.value) {
      step.value = 2
    }
  }

  /** 提交映射（步骤 2 → 3）。 */
  async function submitMapping(): Promise<void> {
    if (!batchId.value) {
      return
    }
    busy.value = true
    errorMessage.value = ''
    try {
      await putMapping(batchId.value, { field_mapping_json: { ...mapping } })
      step.value = 3
    } catch (error) {
      errorMessage.value = messageOf(error)
      throw error
    } finally {
      busy.value = false
    }
  }

  /** 全量校验（步骤 3 → 4）。 */
  async function runValidate(): Promise<void> {
    if (!batchId.value) {
      return
    }
    busy.value = true
    errorMessage.value = ''
    try {
      validation.value = await validateBatch(batchId.value)
      step.value = 4
    } catch (error) {
      errorMessage.value = messageOf(error)
      throw error
    } finally {
      busy.value = false
    }
  }

  /**
   * 确认落库（步骤 4 → 完成）。
   *
   * @param idempotencyKey 一次意图一个键；重试必须复用（`utils/idempotency.ts`）
   */
  async function confirm(idempotencyKey: string): Promise<ReplayableResult<ConfirmResult>> {
    if (!batchId.value) {
      throw new Error('尚未创建导入批次')
    }
    busy.value = true
    errorMessage.value = ''
    try {
      const result = await confirmBatch(batchId.value, idempotencyKey)
      confirmResult.value = result.data
      replayed.value = result.replayed
      step.value = WIZARD_STEPS.length - 1
      return result
    } catch (error) {
      errorMessage.value = messageOf(error)
      throw error
    } finally {
      busy.value = false
    }
  }

  /** 回到初始态（换一个文件重新开始）。 */
  function reset(): void {
    step.value = 0
    busy.value = false
    errorMessage.value = ''
    batch.value = null
    preview.value = null
    for (const key of Object.keys(mapping)) {
      delete mapping[key]
    }
    validation.value = null
    confirmResult.value = null
    replayed.value = false
  }

  return {
    step,
    stepTitle,
    busy,
    errorMessage,
    batch,
    batchId,
    preview,
    mapping,
    validation,
    confirmResult,
    replayed,
    errorCounts,
    upload,
    loadPreview,
    goMapping,
    submitMapping,
    runValidate,
    confirm,
    reset,
  }
}

/** 统一从结构化错误取提示（复用 `utils/error.ts` 的口径）。 */
function messageOf(error: unknown): string {
  const err = error as { code?: string; message?: string } | undefined
  if (!err) {
    return '请求失败，请稍后重试'
  }
  return err.code ? `[${err.code}] ${err.message ?? ''}` : err.message ?? '请求失败'
}
