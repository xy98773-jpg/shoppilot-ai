// 盘点 / 手工调整表单校验单测（`M3-B10` 验收点）。
//
// ★ 这里验证的是**输入完整性**：COUNT 必填 target、MANUAL 必填且非 0 的 delta、原因必填。
//   目标值为负 / 调整后为负属业务规则，由服务端 INV-5005 / INV-5006 判定，前端不复制。
import { describe, expect, it } from 'vitest'
import type { AdjustFormInput } from './adjustForm'
import { buildAdjustPayload, validateAdjustForm } from './adjustForm'

function input(patch: Partial<AdjustFormInput> = {}): AdjustFormInput {
  return {
    warehouseId: 9004,
    skuId: 9002,
    targetOnHandQty: '',
    qtyDelta: '',
    reason: '月度盘点',
    ...patch,
  }
}

describe('validateAdjustForm', () => {
  it('盘点模式：目标值必填', () => {
    const errors = validateAdjustForm('COUNT_ADJUST', input())
    expect(errors).toHaveLength(1)
    expect(errors[0]).toMatchObject({ field: 'target_on_hand_qty' })
    expect(errors[0].message).toContain('必须填写目标库存')
  })

  it('盘点模式：目标值填了就通过（含 0，是否合法由服务端判定）', () => {
    expect(validateAdjustForm('COUNT_ADJUST', input({ targetOnHandQty: '0' }))).toEqual([])
    expect(validateAdjustForm('COUNT_ADJUST', input({ targetOnHandQty: '10.000' }))).toEqual([])
  })

  it('盘点模式：非数字目标值被拦下', () => {
    const errors = validateAdjustForm('COUNT_ADJUST', input({ targetOnHandQty: '十' }))
    expect(errors.some((e) => e.field === 'target_on_hand_qty')).toBe(true)
  })

  it('手工模式：增量为 0 被拦下（服务端 INV-5005 的体验层前置）', () => {
    const errors = validateAdjustForm('MANUAL_ADJUST', input({ qtyDelta: '0' }))
    expect(errors).toHaveLength(1)
    expect(errors[0]).toMatchObject({ field: 'qty_delta' })
    expect(errors[0].message).toContain('不能为 0')
  })

  it('手工模式：带符号增量通过（负数与显式正号都合法）', () => {
    expect(validateAdjustForm('MANUAL_ADJUST', input({ qtyDelta: '-2.500' }))).toEqual([])
    expect(validateAdjustForm('MANUAL_ADJUST', input({ qtyDelta: '+3' }))).toEqual([])
    expect(validateAdjustForm('MANUAL_ADJUST', input({ qtyDelta: '3' }))).toEqual([])
  })

  it('手工模式：非数字 / 纯符号被拦下', () => {
    expect(validateAdjustForm('MANUAL_ADJUST', input({ qtyDelta: '-' }))[0].field).toBe('qty_delta')
    expect(validateAdjustForm('MANUAL_ADJUST', input({ qtyDelta: '1e3' }))[0].field).toBe('qty_delta')
  })

  it('仓库 / SKU / 原因缺失时逐项报错', () => {
    const errors = validateAdjustForm(
      'COUNT_ADJUST',
      input({ warehouseId: null, skuId: 0, targetOnHandQty: '1', reason: '   ' }),
    )
    const fields = errors.map((e) => e.field)
    expect(fields).toContain('warehouse_id')
    expect(fields).toContain('sku_id')
    expect(fields).toContain('reason')
  })
})

describe('buildAdjustPayload', () => {
  it('只提交与模式匹配的数量字段（COUNT 不带 qty_delta）', () => {
    const payload = buildAdjustPayload('COUNT_ADJUST', input({ targetOnHandQty: '8.000', qtyDelta: '-1' }))
    expect(payload).toEqual({
      warehouse_id: 9004,
      sku_id: 9002,
      txn_type: 'COUNT_ADJUST',
      target_on_hand_qty: '8.000',
      reason: '月度盘点',
    })
    expect('qty_delta' in payload).toBe(false)
  })

  it('MANUAL 只带 qty_delta（不带 target_on_hand_qty）', () => {
    const payload = buildAdjustPayload(
      'MANUAL_ADJUST',
      input({ qtyDelta: '-2.500', targetOnHandQty: '99' }),
    )
    expect(payload.qty_delta).toBe('-2.500')
    expect('target_on_hand_qty' in payload).toBe(false)
  })

  it('校验未通过时拒绝装配', () => {
    expect(() => buildAdjustPayload('MANUAL_ADJUST', input({ qtyDelta: '0' }))).toThrow(/qty_delta/)
  })
})
