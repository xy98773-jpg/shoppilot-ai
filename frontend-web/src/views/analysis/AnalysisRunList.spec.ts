// 分析任务列表页单测（M4-B22.2：接入真实 `GET /analysis/runs`）。
//
// 覆盖：
//   1. 首次加载调用列表接口，参数为 `{page:1,pageSize:20}`（默认不带 sort）；
//   2. 分页数据渲染（run_no / 状态文案 / total_skus / risk_sku_count / 进度）；
//   3. 筛选参数构造：status、date_from+date_to；
//   4. 空列表展示空态与新建入口；
//   5. 接口失败时 `ElMessage.error` 提示，表格保持空态；
//   6. 「查看详情」跳转真实行对应的 run_id；
//   7. 后端未返回 created_at 时，创建时间列显示 "-"（不伪造本地时间）；
//   8. 卸载后轮询停止（推进时间不再发请求）。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
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

import { listAnalysisRuns } from '@/api/analysis'
import type { AnalysisRunListItem } from '@/types/analysis'
import AnalysisRunList from './AnalysisRunList.vue'

function makeRow(overrides: Partial<AnalysisRunListItem> = {}): AnalysisRunListItem {
  return {
    run_id: 30001,
    run_no: 'RUN-20261014-0001',
    status: 'SUCCEEDED',
    scope_type: 'ALL_ACTIVE',
    warehouse_id: 9001,
    total_skus: 128,
    processed_skus: 128,
    progress: 100,
    risk_sku_count: 17,
    ...overrides,
  }
}

function pageResult(list: AnalysisRunListItem[], total = list.length) {
  return { list, page: 1, pageSize: 20, total }
}

function mountList() {
  const pinia = createPinia()
  setActivePinia(pinia)
  return mount(AnalysisRunList, { global: { plugins: [pinia, ElementPlus] } })
}

/** 取当前组件里第 `index` 个 el-select（0 = 状态，1 = 排序）。 */
function selectAt(wrapper: ReturnType<typeof mountList>, index: number) {
  const select = wrapper.findAllComponents({ name: 'ElSelect' })[index]
  if (!select) throw new Error(`未找到第 ${index} 个 el-select`)
  return select
}

describe('AnalysisRunList', () => {
  beforeEach(() => {
    pushMock.mockReset()
    localStorage.clear()
    vi.mocked(listAnalysisRuns).mockReset()
    vi.mocked(listAnalysisRuns).mockResolvedValue(pageResult([makeRow()]))
  })

  afterEach(() => {
    vi.restoreAllMocks()
    document.body.innerHTML = ''
  })

  it('首次加载调用列表接口（默认分页、默认不带 sort）并渲染分页数据', async () => {
    const wrapper = mountList()
    await flushPromises()

    // 1) 首次请求参数：只有分页，默认排序交给服务端 created_at,desc
    expect(listAnalysisRuns).toHaveBeenCalledTimes(1)
    expect(vi.mocked(listAnalysisRuns).mock.calls[0][0]).toEqual({ page: 1, pageSize: 20 })

    // 2) 渲染 run_no / 状态文案 / 数量列 / 进度
    const text = wrapper.text()
    expect(text).toContain('RUN-20261014-0001')
    expect(text).toContain('已完成')
    expect(text).toContain('ALL_ACTIVE')
    expect(text).toContain('128')
    expect(text).toContain('17')
    expect(text).toContain('100%')

    // 3) 分页器使用服务端返回的 total
    expect(wrapper.findComponent({ name: 'ElPagination' }).props('total')).toBe(1)

    wrapper.unmount()
  })

  it('查看详情跳转到该行的 run_id', async () => {
    const wrapper = mountList()
    await flushPromises()

    const detailButton = wrapper.findAll('button').find((b) => b.text().trim() === '查看详情')
    expect(detailButton).toBeTruthy()
    await detailButton!.trigger('click')

    expect(pushMock).toHaveBeenCalledWith({
      name: 'analysis-run-detail',
      params: { id: '30001' },
    })

    wrapper.unmount()
  })

  it('选择状态后按 status 重新查询（回到第一页）', async () => {
    const wrapper = mountList()
    await flushPromises()

    const statusSelect = selectAt(wrapper, 0)
    await statusSelect.vm.$emit('update:modelValue', 'FAILED')
    await statusSelect.vm.$emit('change', 'FAILED')
    await flushPromises()

    expect(vi.mocked(listAnalysisRuns).mock.calls.at(-1)?.[0]).toEqual({
      page: 1,
      pageSize: 20,
      status: 'FAILED',
    })

    wrapper.unmount()
  })

  it('选择日期区间后拆成 date_from / date_to 查询', async () => {
    const wrapper = mountList()
    await flushPromises()

    const picker = wrapper.findComponent({ name: 'ElDatePicker' })
    expect(picker.exists()).toBe(true)
    await picker.vm.$emit('update:modelValue', ['2026-10-01', '2026-10-14'])
    await picker.vm.$emit('change', ['2026-10-01', '2026-10-14'])
    await flushPromises()

    expect(vi.mocked(listAnalysisRuns).mock.calls.at(-1)?.[0]).toEqual({
      page: 1,
      pageSize: 20,
      date_from: '2026-10-01',
      date_to: '2026-10-14',
    })

    wrapper.unmount()
  })

  it('空列表展示「暂无分析任务」与新建入口', async () => {
    vi.mocked(listAnalysisRuns).mockResolvedValue(pageResult([], 0))

    const wrapper = mountList()
    await flushPromises()

    expect(wrapper.text()).toContain('暂无分析任务')
    const createButton = wrapper.findAll('button').find((b) => b.text().includes('新建分析任务'))
    expect(createButton).toBeTruthy()
    await createButton!.trigger('click')
    expect(pushMock).toHaveBeenCalledWith({ name: 'analysis-run-create' })

    wrapper.unmount()
  })

  it('接口失败时提示错误，且表格保持空态', async () => {
    const errorSpy = vi.spyOn(ElMessage, 'error')
    vi.mocked(listAnalysisRuns).mockRejectedValue(new Error('网络异常'))

    const wrapper = mountList()
    await flushPromises()

    expect(errorSpy).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('暂无分析任务')

    wrapper.unmount()
  })

  it('后端未返回 created_at 时创建时间列显示「-」', async () => {
    const wrapper = mountList()
    await flushPromises()

    // 行内单元格顺序：运行编号 / 状态 / 范围 / SKU 总数 / 进度 / 风险 SKU 数 / 创建时间 / 操作
    const rows = wrapper.findAll('.el-table__body tbody tr')
    expect(rows.length).toBe(1)
    const cells = rows[0].findAll('td')
    expect(cells[6].text()).toBe('-')

    wrapper.unmount()
  })

  it('页内存在进行中任务时轮询刷新，卸载后停止轮询', async () => {
    vi.useFakeTimers({
      toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'Date'],
    })
    try {
      vi.mocked(listAnalysisRuns).mockResolvedValue(
        pageResult([makeRow({ status: 'RUNNING', progress: 40, risk_sku_count: null })]),
      )

      const wrapper = mountList()
      await flushPromises()

      const callsAfterLoad = vi.mocked(listAnalysisRuns).mock.calls.length
      expect(callsAfterLoad).toBe(1)

      // 2s 后触发一轮静默刷新
      await vi.advanceTimersByTimeAsync(2000)
      await flushPromises()
      const callsAfterPoll = vi.mocked(listAnalysisRuns).mock.calls.length
      expect(callsAfterPoll).toBeGreaterThan(callsAfterLoad)

      // 卸载后继续推进时间（远超退避上限），不应再发请求
      wrapper.unmount()
      await vi.advanceTimersByTimeAsync(60_000)
      await flushPromises()
      expect(vi.mocked(listAnalysisRuns).mock.calls.length).toBe(callsAfterPoll)
    } finally {
      vi.useRealTimers()
    }
  })
})
