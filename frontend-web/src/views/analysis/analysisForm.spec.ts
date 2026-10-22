// 分析创建表单纯逻辑单测（M4-B21b）。
//
// 覆盖：默认日期窗口、SKU 文本解析、请求体形状（params 八字段 / 不含 team_id）、
// 前置校验（日期区间 / min_days 上界 / 算法版本字符集 / 范围必填）。
import { describe, expect, it } from 'vitest'
import {
  buildCreatePayload,
  createDefaultForm,
  defaultDateRange,
  parseSkuIds,
  validateAnalysisForm,
} from './analysisForm'

describe('analysisForm', () => {
  it('默认日期窗口为最近 28 天（含今日）', () => {
    const range = defaultDateRange(new Date(2026, 9, 20), 28)
    expect(range).toEqual({ date_from: '2026-09-23', date_to: '2026-10-20' })
  })

  it('createDefaultForm 使用默认参数与算法版本', () => {
    const form = createDefaultForm(new Date(2026, 9, 20))
    expect(form.scope_type).toBe('ALL_ACTIVE')
    expect(form.window_days).toBe('28')
    expect(form.min_days).toBe('7')
    expect(form.ss_ratio).toBe('0.5')
    expect(form.algorithm_version).toBe('avg-ma-3sigma-v1')
    expect(validateAnalysisForm(form)).toBeNull()
  })

  it('parseSkuIds 支持逗号/空格/中文逗号分隔，丢弃非法项', () => {
    expect(parseSkuIds('5001, 5002 5003；abc；-1，0')).toEqual([5001, 5002, 5003])
    expect(parseSkuIds('   ')).toEqual([])
  })

  it('SELECTED_SKUS 请求体带 scope_json.sku_ids，且不含 team_id', () => {
    const form = {
      ...createDefaultForm(new Date(2026, 9, 20)),
      scope_type: 'SELECTED_SKUS' as const,
      sku_ids: '5001,5002',
      warehouse_id: '100',
      budget_limit: '10000.5',
    }
    const payload = buildCreatePayload(form)

    expect(payload.scope_type).toBe('SELECTED_SKUS')
    expect(payload.scope_json).toEqual({ sku_ids: [5001, 5002] })
    expect(payload.warehouse_id).toBe(100)
    expect(payload).not.toHaveProperty('team_id')
    expect(payload.params).toEqual({
      window_days: 28,
      min_days: 7,
      coverage_days: 7,
      lead_time_default: 7,
      ss_ratio: 0.5,
      cv_high: 1.0,
      budget_limit: 10000.5,
      enable_llm: true,
    })
  })

  it('CATEGORY 请求体带 scope_json.category；关闭 LLM 时 prompt_version 为 null', () => {
    const form = {
      ...createDefaultForm(new Date(2026, 9, 20)),
      scope_type: 'CATEGORY' as const,
      category: ' 箱包 ',
      enable_llm: false,
    }
    const payload = buildCreatePayload(form)
    expect(payload.scope_json).toEqual({ category: '箱包' })
    expect(payload.prompt_version).toBeNull()
  })

  it('校验拦截明显非法输入', () => {
    const base = createDefaultForm(new Date(2026, 9, 20))
    expect(
      validateAnalysisForm({ ...base, date_from: '2026-10-21', date_to: '2026-10-20' }),
    ).toContain('起始日期')
    expect(validateAnalysisForm({ ...base, min_days: '30' })).toContain('最少有效天数')
    expect(validateAnalysisForm({ ...base, window_days: '0' })).toContain('统计窗口')
    expect(validateAnalysisForm({ ...base, algorithm_version: '-bad' })).toContain('算法版本')
    expect(
      validateAnalysisForm({ ...base, scope_type: 'SELECTED_SKUS', sku_ids: '' }),
    ).toContain('SKU ID')
    expect(validateAnalysisForm({ ...base, scope_type: 'CATEGORY', category: ' ' })).toContain(
      '类目',
    )
    expect(validateAnalysisForm({ ...base, budget_limit: '-1' })).toContain('预算上限')
  })
})
