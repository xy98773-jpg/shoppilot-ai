<template>
  <div class="adjust-page">
    <el-card shadow="never">
      <template #header>
        <div class="adjust-page__head">
          <span class="adjust-page__title">盘点 / 手工调整</span>
          <span class="adjust-page__subtitle">同一端点二合一（POST /inventory/adjustments）</span>
        </div>
      </template>

      <el-alert
        class="adjust-page__caliber"
        type="info"
        :closable="false"
        show-icon
        title="盘点按目标值提交（target_on_hand_qty），手工调整按带符号增量提交（qty_delta）"
        description="目标值为负、调整后为负、增量为 0 等判定全部由服务端返回（INV-5005 / INV-5006）；页面只做必填与格式校验。差额为 0 的盘点不写流水，返回 transaction_id 为 null。"
      />

      <el-form label-width="130px" class="adjust-page__form">
        <el-form-item label="调整方式">
          <el-radio-group v-model="mode">
            <el-radio-button value="COUNT_ADJUST">盘点调整（目标值）</el-radio-button>
            <!-- ★ 手工调整仅 OWNER/ADMIN 可见（后端对 MANUAL_ADJUST 额外要求 ADMIN+ → INV-5007） -->
            <el-radio-button v-if="canManual" value="MANUAL_ADJUST">手工调整（增量）</el-radio-button>
          </el-radio-group>
          <span v-if="!canManual" class="adjust-page__hint">
            当前角色不可执行手工调整（需 OWNER/ADMIN）
          </span>
        </el-form-item>

        <el-form-item label="仓库 ID" required :error="errorOf('warehouse_id')">
          <el-input-number v-model="form.warehouseId" :min="1" :precision="0" :controls="false" />
        </el-form-item>
        <el-form-item label="SKU ID" required :error="errorOf('sku_id')">
          <el-input-number v-model="form.skuId" :min="1" :precision="0" :controls="false" />
        </el-form-item>

        <el-form-item
          v-if="mode === 'COUNT_ADJUST'"
          label="目标库存"
          required
          :error="errorOf('target_on_hand_qty')"
        >
          <el-input v-model="form.targetOnHandQty" placeholder="字符串，如 10.000" style="width: 220px" />
        </el-form-item>
        <el-form-item v-else label="调整数量" required :error="errorOf('qty_delta')">
          <el-input v-model="form.qtyDelta" placeholder="带符号，如 -2.500 / 5" style="width: 220px" />
        </el-form-item>

        <el-form-item label="原因" required :error="errorOf('reason')">
          <el-input v-model="form.reason" type="textarea" :rows="3" maxlength="200" show-word-limit />
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="submitting" @click="onSubmit">提交</el-button>
          <el-button @click="onReset">重置</el-button>
        </el-form-item>
      </el-form>

      <el-descriptions v-if="result" :column="2" border size="small" class="adjust-page__result">
        <el-descriptions-item label="流水 ID">
          {{ result.transaction_id === null ? '（差额 0，未写流水）' : result.transaction_id }}
        </el-descriptions-item>
        <el-descriptions-item label="变更量">{{ result.qty_delta }}</el-descriptions-item>
        <el-descriptions-item label="变更前 / 后">
          {{ result.qty_before }} → {{ result.qty_after }}
        </el-descriptions-item>
        <el-descriptions-item label="余额版本">{{ result.balance_version ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="幂等重放">
          {{ replayed ? '是（该操作已提交过，未重复落账）' : '否（本次新执行）' }}
        </el-descriptions-item>
        <el-descriptions-item label="发生时间">{{ formatDateTime(result.occurred_at) }}</el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
</template>

<script setup lang="ts">
// 盘点 / 手工调整二合一（F-INV-03/04）。
//
// ★ 口径：不承载业务规则 —— 只做「必填 / 数字格式 / 增量非 0」的输入完整性校验，
//   目标值为负、调整后为负、权限不足等一律由服务端裁定（INV-5005 / INV-5006 / INV-5007）。
// ★ 数量以字符串原样提交（§9.4），前端不做精确算术。
// ★ 必须带 Idempotency-Key：一次意图一个键，失败重试复用，成功或重置后换新键。
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { adjustInventory } from '@/api/inventory'
import type { AdjustmentMode, AdjustmentResponse } from '@/types/inventory'
import { useTeamStore } from '@/stores/team'
import { hasAnyRole } from '@/utils/permission'
import { describeError } from '@/utils/error'
import { formatDateTime } from '@/utils/format'
import { createIdempotencyIntent } from '@/utils/idempotency'
import {
  buildAdjustPayload,
  validateAdjustForm,
  type AdjustFormInput,
  type FieldError,
} from './adjustForm'

const teamStore = useTeamStore()
/** 手工调整入口显隐（体验层）：后端对 MANUAL_ADJUST 额外要求 ADMIN+（INV-5007）。 */
const canManual = computed(() => hasAnyRole(teamStore.role, ['OWNER', 'ADMIN']))

const mode = ref<AdjustmentMode>('COUNT_ADJUST')

function emptyForm(): AdjustFormInput {
  return {
    warehouseId: null,
    skuId: null,
    targetOnHandQty: '',
    qtyDelta: '',
    reason: '',
  }
}

const form = reactive<AdjustFormInput>(emptyForm())
const errors = ref<FieldError[]>([])
const submitting = ref(false)
const result = ref<AdjustmentResponse | null>(null)
const replayed = ref(false)

/** 一次提交意图的幂等键（失败重试复用）。 */
const intent = createIdempotencyIntent()

function errorOf(field: string): string {
  return errors.value.find((e) => e.field === field)?.message ?? ''
}

async function onSubmit(): Promise<void> {
  errors.value = validateAdjustForm(mode.value, form)
  if (errors.value.length > 0) {
    return
  }
  submitting.value = true
  try {
    const payload = buildAdjustPayload(mode.value, form)
    const outcome = await adjustInventory(payload, intent.key())
    result.value = outcome.data
    replayed.value = outcome.replayed
    if (outcome.replayed) {
      ElMessage.warning('该操作已提交过，服务端返回首次结果，未重复落账')
    } else if (outcome.data.transaction_id === null) {
      ElMessage.success('盘点差异为 0，未写流水（余额未变）')
      intent.reset()
    } else {
      ElMessage.success('调整成功')
      intent.reset()
    }
  } catch (error) {
    // 失败不重置键：重试同一意图必须复用同一 Idempotency-Key
    ElMessage.error(describeError(error))
  } finally {
    submitting.value = false
  }
}

function onReset(): void {
  Object.assign(form, emptyForm())
  errors.value = []
  result.value = null
  replayed.value = false
  intent.reset()
  mode.value = 'COUNT_ADJUST'
}
</script>

<style scoped>
.adjust-page__head {
  display: flex;
  align-items: baseline;
  gap: 12px;
}
.adjust-page__title {
  font-weight: 600;
}
.adjust-page__subtitle {
  color: #909399;
  font-size: 12px;
}
.adjust-page__caliber {
  margin-bottom: 16px;
}
.adjust-page__form {
  max-width: 720px;
}
.adjust-page__hint {
  margin-left: 12px;
  color: #909399;
  font-size: 12px;
}
.adjust-page__result {
  margin-top: 16px;
}
</style>
