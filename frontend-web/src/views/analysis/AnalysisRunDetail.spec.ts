// 分析任务详情页单测（M4-B21b）。
//
// 覆盖：
//   1. 中间态每 2s 轮询一次；终态后不再请求（硬验收项）；
//   2. 页面 unmount 后 advanceTimersByTime 不再发起请求（硬验收项）；
//   3. 数量列直显后端字符串（"8.000" / "110.000"），不做前端精度换算；
//   4. `ANL-7012`（结果未就绪）只提示继续等待，不当作失败。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import '../../tests/setupDom'

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: '30001' } }),
  useRouter: () => ({ push: vi.fn() }),
}))

vi.mock('@/api/analysis', () => ({
  getAnalysisRun: vi.fn(),
  listAnalysisRunItems: vi.fn(),
  cancelAnalysisRun: vi.fn(),
  createAnalysisRun: vi.fn(),
  listAnalysisRuns: vi.fn(),
}))

import { getAnalysisRun, listAnalysisRunItems } from '@/api/analysis'
import AnalysisRunDetail from './AnalysisRunDetail.vue'

const runningResponse = {
  run_id: 30001,
  run_no: 'RUN-20261014-0002',
  status: 'RUNNING' as const,
  scope_type: 'SELECTED_SKUS' as const,
  warehouse_id: 100,
  total_skus: 3,
  processed_skus: 2,
  progress: 66,
  current_step: 'LLM_EXPLAIN',
  llm_status: 'PENDING' as const,
  timeout_at: '2026-10-14T08:05:00.000Z',
  started_at: '2026-10-14T08:00:01.000Z',
}

const succeededResponse = {
  ...runningResponse,
  status: 'SUCCEEDED' as const,
  processed_skus: 3,
  progress: 100,
  current_step: 'DONE',
  llm_status: 'SUCCEEDED' as const,
  risk_sku_count: 2,
  suggestion_count: 1,
  data_start_date: '2026-09-16',
  data_end_date: '2026-10-13',
  finished_at: '2026-10-14T08:00:12.000Z',
}

const itemRow = {
  id: 41001,
  run_id: 30001,
  sku_id: 5001,
  sku_code: 'SKU-BAG-001-BLK',
  warehouse_id: 100,
  on_hand_qty: '8.000',
  available_qty: '8.000',
  avg_daily_sales: '6.250',
  lead_time_days: 7,
  coverage_days: 7,
  qty_base: '104.500',
  qty_suggest: '110.000',
  risk_level: 'HIGH',
  risk_types: ['STOCKOUT'],
  llm_status: 'SUCCEEDED',
  warnings: [],
}

function mountDetail() {
  const pinia = createPinia()
  setActivePinia(pinia)
  return mount(AnalysisRunDetail, { global: { plugins: [pinia, ElementPlus] } })
}

describe('AnalysisRunDetail 轮询与结果快照', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.mocked(listAnalysisRunItems).mockResolvedValue({
      list: [itemRow],
      page: 1,
      pageSize: 20,
      total: 1,
    })
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
    document.body.innerHTML = ''
  })

  it('中间态每 2s 轮询；到达终态后不再请求', async () => {
    vi.mocked(getAnalysisRun)
      .mockResolvedValueOnce(runningResponse)
      .mockResolvedValueOnce(succeededResponse)

    const wrapper = mountDetail()
    await flushPromises()
    expect(getAnalysisRun).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(2000)
    await flushPromises()
    expect(getAnalysisRun).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('已完成')

    // 终态后再推进 5 分钟：不再请求（退避也不该再触发）
    await vi.advanceTimersByTimeAsync(300_000)
    await flushPromises()
    expect(getAnalysisRun).toHaveBeenCalledTimes(2)

    wrapper.unmount()
  })

  it('页面卸载后不再发起请求（unmount 停轮询）', async () => {
    vi.mocked(getAnalysisRun).mockResolvedValue(runningResponse)

    const wrapper = mountDetail()
    await flushPromises()
    expect(getAnalysisRun).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(2000)
    await flushPromises()
    expect(getAnalysisRun).toHaveBeenCalledTimes(2)

    wrapper.unmount()

    await vi.advanceTimersByTimeAsync(300_000)
    await flushPromises()
    expect(getAnalysisRun).toHaveBeenCalledTimes(2)
  })

  it('数量列原样展示后端字符串，不做精度换算', async () => {
    vi.mocked(getAnalysisRun).mockResolvedValue(succeededResponse)

    const wrapper = mountDetail()
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('SKU-BAG-001-BLK')
    expect(text).toContain('8.000')
    expect(text).toContain('6.250')
    expect(text).toContain('110.000')

    wrapper.unmount()
  })

  it('items 返回 ANL-7012 时提示继续等待，不当作失败', async () => {
    vi.mocked(getAnalysisRun).mockResolvedValue(succeededResponse)
    vi.mocked(listAnalysisRunItems).mockRejectedValue({
      code: 'ANL-7012',
      message: '分析结果尚未生成，请等待任务完成',
    })
    const infoSpy = vi.spyOn(ElMessage, 'info')

    const wrapper = mountDetail()
    await flushPromises()

    expect(infoSpy).toHaveBeenCalledWith('分析结果尚未生成，请等待任务完成（已继续轮询）')
    expect(wrapper.text()).not.toContain('[ANL-7012]')

    wrapper.unmount()
  })
})
