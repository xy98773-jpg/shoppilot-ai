// 导入批次错误分页 / 导出参数拼接（`M3-B10` 验收点）。
//
// ★ 关键不变量：导出参数必须与 `GET /{id}/errors` 的筛选语义**一致**（导出当前筛选结果），
//   且空值不能被拼进查询串（否则后端会把空串当筛选条件）。
import { describe, expect, it } from 'vitest'
import {
  buildBatchQuery,
  buildErrorExportParams,
  buildErrorQuery,
  pruneFilter,
} from './batchQuery'

describe('buildBatchQuery', () => {
  it('空筛选不传（只保留分页）', () => {
    expect(buildBatchQuery({ status: '', bizType: '', from: '', to: '' }, 1, 20)).toEqual({
      page: 1,
      pageSize: 20,
    })
  })

  it('逐项拼接筛选（状态 / 业务类型 / 起止日）', () => {
    expect(
      buildBatchQuery(
        { status: 'COMPLETED', bizType: 'SALES_DAILY', from: '2026-10-01', to: '2026-10-18' },
        2,
        50,
      ),
    ).toEqual({
      page: 2,
      pageSize: 50,
      status: 'COMPLETED',
      bizType: 'SALES_DAILY',
      from: '2026-10-01',
      to: '2026-10-18',
    })
  })
})

describe('buildErrorQuery（分页）', () => {
  it('分页 + 级别 + 行号', () => {
    expect(buildErrorQuery(3, 50, 'ERROR', '45')).toEqual({
      page: 3,
      pageSize: 50,
      errorLevel: 'ERROR',
      rowNo: 45,
    })
  })

  it('级别为空 / 行号空串不传', () => {
    expect(buildErrorQuery(1, 20, '', '')).toEqual({ page: 1, pageSize: 20 })
    expect(buildErrorQuery(1, 20)).toEqual({ page: 1, pageSize: 20 })
  })

  it('行号脏值（非数字 / 0 / 负数）不传给后端', () => {
    expect(buildErrorQuery(1, 20, 'WARNING', 'abc')).toEqual({
      page: 1,
      pageSize: 20,
      errorLevel: 'WARNING',
    })
    expect(buildErrorQuery(1, 20, '', '0')).toEqual({ page: 1, pageSize: 20 })
    expect(buildErrorQuery(1, 20, '', '-5')).toEqual({ page: 1, pageSize: 20 })
  })
})

describe('buildErrorExportParams（导出）', () => {
  it('与错误分页共用同一筛选口径，但**不带分页参数**', () => {
    const params = buildErrorExportParams('ERROR', '45')
    expect(params).toEqual({ errorLevel: 'ERROR', rowNo: 45 })
    expect('page' in params).toBe(false)
    expect('pageSize' in params).toBe(false)
  })

  it('无筛选时导出全量（空对象）', () => {
    expect(buildErrorExportParams()).toEqual({})
    expect(buildErrorExportParams('', '')).toEqual({})
  })
})

describe('pruneFilter', () => {
  it('剔除空串 / null / undefined，保留 0 与 false', () => {
    expect(pruneFilter({ a: '', b: null, c: undefined, d: 0, e: false, f: 'x' })).toEqual({
      d: 0,
      e: false,
      f: 'x',
    })
  })
})
