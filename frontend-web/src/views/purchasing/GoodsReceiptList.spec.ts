// 到货单列表页单测（M6-T11 + M8-R200）。
//
// 覆盖：
//   1. 首次加载按默认分页请求 `GET /purchasing/receipts` 并渲染行数据；
//   2. 筛选（采购单 ID / 状态 / 到货单号）后重新请求，且回到第一页；
//   3. 空列表展示「暂无到货单」；
//   4. 「详情」跳转该行 goods_receipt_id；
//   5. M8-R200：「登记到货」入口按角色显隐；登记成功后刷新列表并跳到新到货单详情。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
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
  createPurchaseOrderFromReplenishment: vi.fn(),
  listGoodsReceipts: vi.fn(),
  getGoodsReceipt: vi.fn(),
  createGoodsReceipt: vi.fn(),
  confirmGoodsReceipt: vi.fn(),
  cancelGoodsReceipt: vi.fn(),
}))

import { listGoodsReceipts } from '@/api/purchasing'
import { useTeamStore } from '@/stores/team'
import type { GoodsReceiptSummary } from '@/types/purchasing'
import GoodsReceiptCreateDialog from './GoodsReceiptCreateDialog.vue'
import GoodsReceiptList from './GoodsReceiptList.vue'

function makeRow(overrides: Partial<GoodsReceiptSummary> = {}): GoodsReceiptSummary {
  return {
    goods_receipt_id: 72001,
    receipt_no: 'GR-20261021-0001',
    purchase_order_id: 30001,
    warehouse_id: 9004,
    status: 'DRAFT',
    receipt_date: '2026-10-21',
    total_qty: '40.000',
    total_amount: '800.00',
    remark: null,
    version: 1,
    created_at: '2026-10-21T03:00:00.000Z',
    ...overrides,
  }
}

function pageResult(list: GoodsReceiptSummary[], total = list.length) {
  return { list, page: 1, pageSize: 20, total }
}

function mountList() {
  const pinia = createPinia()
  setActivePinia(pinia)
  return mount(GoodsReceiptList, { global: { plugins: [pinia, ElementPlus] } })
}

describe('GoodsReceiptList', () => {
  beforeEach(() => {
    pushMock.mockReset()
    vi.mocked(listGoodsReceipts).mockReset()
    vi.mocked(listGoodsReceipts).mockResolvedValue(pageResult([makeRow()]))
  })

  afterEach(() => {
    vi.restoreAllMocks()
    document.body.innerHTML = ''
  })

  it('首次加载按默认分页请求并渲染到货单行（数量/金额原样字符串）', async () => {
    const wrapper = mountList()
    await flushPromises()

    expect(listGoodsReceipts).toHaveBeenCalledTimes(1)
    expect(vi.mocked(listGoodsReceipts).mock.calls[0][0]).toEqual({ page: 1, pageSize: 20 })

    const text = wrapper.text()
    expect(text).toContain('GR-20261021-0001')
    expect(text).toContain('草稿')
    expect(text).toContain('30001')
    expect(text).toContain('40.000')
    expect(text).toContain('800.00')

    const rows = wrapper.findAll('[data-testid="gr-table"] .el-table__body tbody tr')
    expect(rows.length).toBe(1)

    wrapper.unmount()
  })

  it('筛选采购单 ID / 状态 / 到货单号后重新请求（回到第一页）', async () => {
    const wrapper = mountList()
    await flushPromises()

    // ElInput 的 inheritAttrs=false ⇒ data-testid 直接落在内层 <input> 上
    await wrapper.find('[data-testid="gr-filter-po"]').setValue('30001')
    await wrapper.find('[data-testid="gr-filter-no"]').setValue(' GR-2026 ')
    await wrapper.find('[data-testid="gr-search"]').trigger('click')
    await flushPromises()

    expect(vi.mocked(listGoodsReceipts).mock.calls.at(-1)?.[0]).toEqual({
      page: 1,
      pageSize: 20,
      purchase_order_id: 30001,
      receipt_no: 'GR-2026',
    })

    const statusSelect = wrapper.findAllComponents({ name: 'ElSelect' })[0]
    await statusSelect.vm.$emit('update:modelValue', 'CONFIRMED')
    await statusSelect.vm.$emit('change', 'CONFIRMED')
    await flushPromises()

    expect(vi.mocked(listGoodsReceipts).mock.calls.at(-1)?.[0]).toEqual({
      page: 1,
      pageSize: 20,
      purchase_order_id: 30001,
      receipt_no: 'GR-2026',
      status: 'CONFIRMED',
    })

    wrapper.unmount()
  })

  it('空列表展示「暂无到货单」空态', async () => {
    vi.mocked(listGoodsReceipts).mockResolvedValue(pageResult([], 0))

    const wrapper = mountList()
    await flushPromises()

    expect(wrapper.find('[data-testid="gr-empty"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('暂无到货单')

    wrapper.unmount()
  })

  it('点击行内「详情」按 goods_receipt_id 跳转到详情路由', async () => {
    const wrapper = mountList()
    await flushPromises()

    await wrapper.find('[data-testid="gr-row-detail"]').trigger('click')

    expect(pushMock).toHaveBeenCalledWith({
      name: 'purchasing-receipt-detail',
      params: { id: '72001' },
    })

    wrapper.unmount()
  })

  it('「登记到货」入口仅对 OPERATOR+ 可见（体验层，服务端才是边界）', async () => {
    const wrapper = mountList()
    await flushPromises()

    // 角色未水合 ⇒ 一律按无权限处理（不出现「未加载即放行」）
    expect(wrapper.find('[data-testid="gr-create-open"]').exists()).toBe(false)

    useTeamStore().setRole('OPERATOR')
    await flushPromises()
    expect(wrapper.find('[data-testid="gr-create-open"]').exists()).toBe(true)

    useTeamStore().setRole('VIEWER')
    await flushPromises()
    expect(wrapper.find('[data-testid="gr-create-open"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('登记成功回调：刷新列表并跳转到新到货单详情（草稿下一步就是确认入库）', async () => {
    const wrapper = mountList()
    await flushPromises()
    expect(listGoodsReceipts).toHaveBeenCalledTimes(1)

    wrapper.findComponent(GoodsReceiptCreateDialog).vm.$emit('created', 72099, 'GR-20261022-0002', false)
    await flushPromises()

    // 刷新一次
    expect(listGoodsReceipts).toHaveBeenCalledTimes(2)
    expect(pushMock).toHaveBeenCalledWith({
      name: 'purchasing-receipt-detail',
      params: { id: '72099' },
    })

    wrapper.unmount()
  })
})
