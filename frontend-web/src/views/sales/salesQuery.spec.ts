// 销售列表筛选参数拼接（`M3-B10` 验收点）。
//
// ★ 后端参数名是 `dateFrom` / `dateTo`（驼峰），本用例把这一点固定住：
//   任何人"顺手"改成 snake_case 都会在这里失败（而不是悄悄失效）。
import { describe, expect, it } from 'vitest'
import { buildSalesQuery, toPositiveInt } from './salesQuery'

describe('buildSalesQuery', () => {
  it('空筛选只带分页', () => {
    expect(buildSalesQuery({ sku_id: '', warehouse_id: '', dateFrom: '', dateTo: '' }, 1, 20)).toEqual({
      page: 1,
      pageSize: 20,
    })
  })

  it('SKU / 仓库 ID 字符串安全转数字', () => {
    expect(buildSalesQuery({ sku_id: '9002', warehouse_id: '9004' }, 2, 50)).toEqual({
      page: 2,
      pageSize: 50,
      sku_id: 9002,
      warehouse_id: 9004,
    })
  })

  it('日期参数保持驼峰命名（dateFrom / dateTo）', () => {
    const query = buildSalesQuery(
      { sku_id: '', warehouse_id: '', dateFrom: '2026-10-01', dateTo: '2026-10-18' },
      1,
      20,
    )
    expect(query.dateFrom).toBe('2026-10-01')
    expect(query.dateTo).toBe('2026-10-18')
    expect('date_from' in query).toBe(false)
    expect('date_to' in query).toBe(false)
  })

  it('不传 groupBy（本批只展示原始行）', () => {
    const query = buildSalesQuery({}, 1, 20)
    expect('groupBy' in query).toBe(false)
  })
})

describe('toPositiveInt', () => {
  it('空 / 非数字 / 非正数 → undefined', () => {
    expect(toPositiveInt('')).toBeUndefined()
    expect(toPositiveInt(undefined)).toBeUndefined()
    expect(toPositiveInt('abc')).toBeUndefined()
    expect(toPositiveInt('0')).toBeUndefined()
    expect(toPositiveInt('-3')).toBeUndefined()
  })

  it('正数取整（小数输入不产生非法 ID）', () => {
    expect(toPositiveInt('9002')).toBe(9002)
    expect(toPositiveInt('9.7')).toBe(9)
    expect(toPositiveInt(12)).toBe(12)
  })
})
