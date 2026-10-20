// 分析任务列表页单测（M4-B21b）。
//
// 覆盖（项目负责人口径 1：列表 API 尚不存在，禁止调用 GET /analysis/runs）：
//   1. 空态展示 + 新建入口；
//   2. 不调用 `listAnalysisRuns`（后端无该端点）；
//   3. 输入 run_id 跳转详情；非数字被拦截；
//   4. 本地「最近创建」渲染并可打开。
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
import AnalysisRunList from './AnalysisRunList.vue'

function mountList() {
  const pinia = createPinia()
  setActivePinia(pinia)
  return mount(AnalysisRunList, { global: { plugins: [pinia, ElementPlus] } })
}

describe('AnalysisRunList', () => {
  beforeEach(() => {
    pushMock.mockReset()
    localStorage.clear()
  })

  afterEach(() => {
    vi.restoreAllMocks()
    document.body.innerHTML = ''
  })

  it('空态展示新建入口，且不调用不存在的列表接口', async () => {
    const wrapper = mountList()
    await flushPromises()

    expect(wrapper.text()).toContain('本机还没有创建记录')
    const createButtons = wrapper
      .findAll('button')
      .filter((b) => b.text().includes('新建分析任务'))
    expect(createButtons.length).toBeGreaterThan(0)

    await createButtons[0].trigger('click')
    expect(pushMock).toHaveBeenCalledWith({ name: 'analysis-run-create' })
    expect(listAnalysisRuns).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('输入 run_id 跳转详情；非数字被拦截', async () => {
    const wrapper = mountList()
    await flushPromises()

    const input = wrapper.find('input')
    await input.setValue('30001')
    const openButton = wrapper.findAll('button').find((b) => b.text().trim() === '打开')
    expect(openButton).toBeTruthy()
    await openButton!.trigger('click')
    expect(pushMock).toHaveBeenCalledWith({
      name: 'analysis-run-detail',
      params: { id: '30001' },
    })

    pushMock.mockReset()
    const warningSpy = vi.spyOn(ElMessage, 'warning')
    await input.setValue('abc')
    await openButton!.trigger('click')
    expect(warningSpy).toHaveBeenCalledWith('run_id 必须是数字')
    expect(pushMock).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('本地最近创建可渲染并打开', async () => {
    localStorage.setItem(
      'shoppilot_analysis_recent_runs',
      JSON.stringify([
        {
          run_id: 30001,
          run_no: 'RUN-20261014-0002',
          status: 'RUNNING',
          created_at: '2026-10-14T08:00:00.000Z',
        },
      ]),
    )

    const wrapper = mountList()
    await flushPromises()

    expect(wrapper.text()).toContain('RUN-20261014-0002')
    const viewButton = wrapper.findAll('button').find((b) => b.text().trim() === '查看进度')
    expect(viewButton).toBeTruthy()
    await viewButton!.trigger('click')
    expect(pushMock).toHaveBeenCalledWith({
      name: 'analysis-run-detail',
      params: { id: '30001' },
    })

    wrapper.unmount()
  })
})
