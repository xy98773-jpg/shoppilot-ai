// 新建分析任务页单测（M4-B21b）。
//
// 覆盖：
//   1. 提交失败后重试复用**同一个** Idempotency-Key（一次表单会话一个键）；
//   2. 请求体形状：scope_type / 日期 / params 全量 / 不含 team_id；
//   3. 成功后写入本地「最近创建」并跳转详情页。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import '../../tests/setupDom'

const { pushMock } = vi.hoisted(() => ({ pushMock: vi.fn() }))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: pushMock }),
}))

vi.mock('@/api/analysis', () => ({
  createAnalysisRun: vi.fn(),
  getAnalysisRun: vi.fn(),
  listAnalysisRunItems: vi.fn(),
  cancelAnalysisRun: vi.fn(),
  listAnalysisRuns: vi.fn(),
}))

import { createAnalysisRun } from '@/api/analysis'
import AnalysisRunCreate from './AnalysisRunCreate.vue'

function mountCreate() {
  const pinia = createPinia()
  setActivePinia(pinia)
  return mount(AnalysisRunCreate, { global: { plugins: [pinia, ElementPlus] } })
}

function submitButton(wrapper: ReturnType<typeof mount>) {
  const button = wrapper.findAll('button').find((b) => b.text().includes('创建并开始分析'))
  if (!button) throw new Error('未找到提交按钮')
  return button
}

describe('AnalysisRunCreate 幂等键与请求体', () => {
  beforeEach(() => {
    pushMock.mockReset()
    localStorage.clear()
  })

  afterEach(() => {
    vi.restoreAllMocks()
    document.body.innerHTML = ''
  })

  it('提交失败后重试复用同一幂等键，请求体一致', async () => {
    vi.mocked(createAnalysisRun)
      .mockRejectedValueOnce(new Error('network down'))
      .mockResolvedValueOnce({
        data: {
          run_id: 30001,
          run_no: 'RUN-20261014-0002',
          status: 'RUNNING',
          scope_type: 'ALL_ACTIVE',
          total_skus: 0,
          processed_skus: 0,
          progress: 0,
          current_step: 'VALIDATING',
          llm_status: 'PENDING',
          timeout_at: '2026-10-14T08:05:00.000Z',
          created_at: '2026-10-14T08:00:00.000Z',
        },
        replayed: false,
      })

    const wrapper = mountCreate()

    await submitButton(wrapper).trigger('click')
    await flushPromises()
    expect(createAnalysisRun).toHaveBeenCalledTimes(1)

    await submitButton(wrapper).trigger('click')
    await flushPromises()
    expect(createAnalysisRun).toHaveBeenCalledTimes(2)

    const [firstPayload, firstKey] = vi.mocked(createAnalysisRun).mock.calls[0]
    const [secondPayload, secondKey] = vi.mocked(createAnalysisRun).mock.calls[1]

    expect(firstKey).toBeTruthy()
    expect(secondKey).toBe(firstKey)
    expect(secondPayload).toEqual(firstPayload)

    // 请求体形状：不含 team_id；params 八字段齐全；算法版本默认值
    expect(secondPayload).not.toHaveProperty('team_id')
    expect(secondPayload.scope_type).toBe('ALL_ACTIVE')
    expect(secondPayload.date_from).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    expect(secondPayload.date_to).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    expect(secondPayload.date_from <= secondPayload.date_to).toBe(true)
    expect(secondPayload.params).toEqual({
      window_days: 28,
      min_days: 7,
      coverage_days: 7,
      lead_time_default: 7,
      ss_ratio: '0.5',
      cv_high: '1.0',
      budget_limit: null,
      enable_llm: true,
    })
    expect(secondPayload.algorithm_version).toBe('avg-ma-3sigma-v1')
    expect(secondPayload.prompt_version).toBe('explain_risk.v1')

    // 成功后：本地最近创建 + 跳转详情页
    const stored = JSON.parse(localStorage.getItem('shoppilot_analysis_recent_runs') || '[]')
    expect(stored[0].run_id).toBe(30001)
    expect(pushMock).toHaveBeenCalledWith({
      name: 'analysis-run-detail',
      params: { id: '30001' },
    })

    wrapper.unmount()
  })
})
