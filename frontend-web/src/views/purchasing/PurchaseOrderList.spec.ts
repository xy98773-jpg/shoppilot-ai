// 采购单列表页单测（M6-T11）。
//
// 覆盖：
//   1. 首次加载调用 `GET /purchasing/orders`（默认分页、无筛选）并渲染行数据；
//   2. 筛选（供应商 ID + 状态）触发重新请求，且回到第一页；
//   3. 分页切换带上新页码；
//   4. 空列表展示空态；
//   5. 接口失败时 `ElMessage.error` 提示，表格保持空态；
//   6. 「详情」跳转该行 purchase_order_id。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import '../../tests/setupDom'

const { pushMock } = vi.hoisted(() => ({ pushMock: vi.fn() }))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: pushMock }),
}))

vi.mock('@/api/purchasing', () => ({
  listPurchaseOrders: vi.fn(),
  getPurchaseOrder: vi.fn(),
  confirmPurchaseOrder: vi.fn(),
  orderPurchaseOrder: vi.fn(),
  cancelPurchaseOrder: vi.fn(),
  listGoodsReceipts: vi.fn(),
  getGoodsReceipt: vi.fn(),
  confirmGoodsReceipt: vi.fn(),
  cancelGoodsReceipt: vi.fn(),
}))

import { listPurchaseOrders } from '@/api/purchasing'
import type { PurchaseOrderSummary } from '@/types/purchasing'
import PurchaseOrderList from './PurchaseOrderList.vue'

function makeRow(overrides: Partial<PurchaseOrderSummary> = {}): PurchaseOrderSummary {
  return {
    purchase_order_id: 30001,
    po_no: 'PO-20261021-0001',
    status: 'ORDERED',
    source: 'REPLENISHMENT',
    supplier_id: 9001,
    warehouse_id: 9004,
    expected_arrival_date: '2026-10-30',
    total_qty: '12.000',
    received_qty: '3.000',
    total_amount: '1200.00',
    remark: null,
    version: 2,
    created_at: '2026-10-21T02:00:00.000Z',
    ...overrides,
  }
}

function pageResult(list: PurchaseOrderSummary[], total = list.length) {
  return { list, page: 1, pageSize: 20, total }
}

function mountList() {
  const pinia = createPinia()
  setActivePinia(pinia)
  return mount(PurchaseOrderList, { global: { plugins: [pinia, ElementPlus] } })
}

describe('PurchaseOrderList', () => {
  beforeEach(() => {
    pushMock.mockReset()
    vi.mocked(listPurchaseOrders).mockReset()
    vi.mocked(listPurchaseOrders).mockResolvedValue(pageResult([makeRow()]))
  })

  afterEach(() => {
    vi.restoreAllMocks()
    document.body.innerHTML = ''
  })

  it('首次加载按默认分页请求并渲染采购单行（数量/金额原样字符串）', async () => {
    const wrapper = mountList()
    await flushPromises()

    expect(listPurchaseOrders).toHaveBeenCalledTimes(1)
    expect(vi.mocked(listPurchaseOrders).mock.calls[0][0]).toEqual({ page: 1, pageSize: 20 })

    const text = wrapper.text()
    expect(text).toContain('PO-20261021-0001')
    expect(text).toContain('采购中')
    expect(text).toContain('12.000')
    expect(text).toContain('3.000')
    expect(text).toContain('1200.00')
    expect(text).toContain('共 1 条')

    const rows = wrapper.findAll('[data-testid="po-table"] .el-table__body tbody tr')
    expect(rows.length).toBe(1)

    wrapper.unmount()
  })

  it('筛选供应商 ID 与状态后重新请求（回到第一页）', async () => {
    const wrapper = mountList()
    await flushPromises()

    // ElInput 的 inheritAttrs=false ⇒ data-testid 直接落在内层 <input> 上
    await wrapper.find('[data-testid="po-filter-supplier"]').setValue('9001')
    await wrapper.find('[data-testid="po-search"]').trigger('click')
    await flushPromises()

    expect(vi.mocked(listPurchaseOrders).mock.calls.at(-1)?.[0]).toEqual({
      page: 1,
      pageSize: 20,
      supplier_id: 9001,
    })

    const statusSelect = wrapper.findAllComponents({ name: 'ElSelect' })[0]
    await statusSelect.vm.$emit('update:modelValue', 'RECEIVED')
    await statusSelect.vm.$emit('change', 'RECEIVED')
    await flushPromises()

    expect(vi.mocked(listPurchaseOrders).mock.calls.at(-1)?.[0]).toEqual({
      page: 1,
      pageSize: 20,
      supplier_id: 9001,
      status: 'RECEIVED',
    })

    wrapper.unmount()
  })

  it('分页切换到第二页时带上新页码', async () => {
    vi.mocked(listPurchaseOrders).mockResolvedValue(pageResult([makeRow()], 45))

    const wrapper = mountList()
    await flushPromises()

    const pager = wrapper.findComponent({ name: 'ElPagination' })
    expect(pager.props('total')).toBe(45)
    await pager.vm.$emit('current-change', 2)
    await flushPromises()

    expect(vi.mocked(listPurchaseOrders).mock.calls.at(-1)?.[0]).toEqual({ page: 2, pageSize: 20 })

    wrapper.unmount()
  })

  it('空列表展示「暂无采购单」空态', async () => {
    vi.mocked(listPurchaseOrders).mockResolvedValue(pageResult([], 0))

    const wrapper = mountList()
    await flushPromises()

    expect(wrapper.find('[data-testid="po-empty"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('暂无采购单')

    wrapper.unmount()
  })

  it('接口失败时提示错误码与文案，表格保持空态', async () => {
    const errorSpy = vi.spyOn(ElMessage, 'error')
    vi.mocked(listPurchaseOrders).mockRejectedValue({
      code: 'COM-0001',
      message: '参数校验失败',
    })

    const wrapper = mountList()
    await flushPromises()

    expect(errorSpy).toHaveBeenCalledWith('[COM-0001] 参数校验失败')
    expect(wrapper.find('[data-testid="po-empty"]').exists()).toBe(true)

    wrapper.unmount()
  })

  it('点击行内「详情」按 purchase_order_id 跳转到详情路由', async () => {
    const wrapper = mountList()
    await flushPromises()

    await wrapper.find('[data-testid="po-row-detail"]').trigger('click')

    expect(pushMock).toHaveBeenCalledWith({
      name: 'purchasing-order-detail',
      params: { id: '30001' },
    })

    wrapper.unmount()
  })
})
