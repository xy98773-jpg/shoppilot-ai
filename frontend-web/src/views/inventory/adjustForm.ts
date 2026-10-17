// 盘点 / 手工调整的表单校验与请求体装配（纯函数，无副作用）。
//
// ★ 边界声明（PROJECT_STRUCTURE §6.1 / 口径铁律 ①）：
//   这里只做**输入完整性**校验（必填、数字格式、增量非 0），属于体验层的"少跑一趟"，
//   **不是**业务规则：目标值为负、调整后为负等判定由服务端返回 `INV-5005` / `INV-5006`，
//   前端不得复制这些规则，也不得据此静默改写用户输入。
// ★ 数量以字符串原样提交（§9.4），不在前端做精确算术（不用 Number 计算差额）。
import type { AdjustmentMode, AdjustmentRequest } from '@/types/inventory'

/** 表单原始状态（数字输入框可能给 number，文本给 string）。 */
export interface AdjustFormInput {
  warehouseId: number | null
  skuId: number | null
  /** 盘点目标值（字符串，原样提交）。 */
  targetOnHandQty: string
  /** 手工增量（字符串，带符号，原样提交）。 */
  qtyDelta: string
  reason: string
}

/** 字段级校验错误（与后端 `details[]` 同形，便于统一展示）。 */
export interface FieldError {
  field: string
  message: string
}

/** 十进制字符串（允许显式正负号与小数；不接受科学计数法、空、纯符号）。 */
const DECIMAL_RE = /^[+-]?\d+(\.\d+)?$/

/**
 * 校验调整表单。
 *
 * @param mode  `COUNT_ADJUST` 需 `target_on_hand_qty`；`MANUAL_ADJUST` 需非 0 的 `qty_delta`
 * @returns 字段级错误列表（空数组 = 可提交）
 */
export function validateAdjustForm(
  mode: AdjustmentMode,
  input: AdjustFormInput,
): FieldError[] {
  const errors: FieldError[] = []

  if (input.warehouseId === null || input.warehouseId === undefined || input.warehouseId <= 0) {
    errors.push({ field: 'warehouse_id', message: '请填写仓库 ID' })
  }
  if (input.skuId === null || input.skuId === undefined || input.skuId <= 0) {
    errors.push({ field: 'sku_id', message: '请填写 SKU ID' })
  }

  if (mode === 'COUNT_ADJUST') {
    const target = input.targetOnHandQty.trim()
    if (!target) {
      errors.push({ field: 'target_on_hand_qty', message: '盘点调整必须填写目标库存' })
    } else if (!DECIMAL_RE.test(target)) {
      errors.push({ field: 'target_on_hand_qty', message: '目标库存须为数字，如 10.000' })
    }
  } else {
    const delta = input.qtyDelta.trim()
    if (!delta) {
      errors.push({ field: 'qty_delta', message: '手工调整必须填写调整数量' })
    } else if (!DECIMAL_RE.test(delta)) {
      errors.push({ field: 'qty_delta', message: '调整数量须为带符号数字，如 -2.500' })
    } else if (Number(delta) === 0) {
      errors.push({ field: 'qty_delta', message: '调整数量不能为 0' })
    }
  }

  if (!input.reason.trim()) {
    errors.push({ field: 'reason', message: '必须填写调整原因' })
  }

  return errors
}

/**
 * 装配请求体：**只**带与模式匹配的数量字段（避免把 `target` 当 `delta` 发给服务端）。
 *
 * @throws Error 表单未通过校验时（调用方应先用 `validateAdjustForm`）
 */
export function buildAdjustPayload(
  mode: AdjustmentMode,
  input: AdjustFormInput,
): AdjustmentRequest {
  const errors = validateAdjustForm(mode, input)
  if (errors.length > 0) {
    throw new Error(errors.map((e) => `${e.field}: ${e.message}`).join('；'))
  }
  const payload: AdjustmentRequest = {
    warehouse_id: input.warehouseId as number,
    sku_id: input.skuId as number,
    txn_type: mode,
    reason: input.reason.trim(),
  }
  if (mode === 'COUNT_ADJUST') {
    payload.target_on_hand_qty = input.targetOnHandQty.trim()
  } else {
    payload.qty_delta = input.qtyDelta.trim()
  }
  return payload
}
