// 采购单详情页单测（M6-T11）。
//
// 覆盖：
//   1. 明细与头部渲染（含 created_at；数量/单价/金额原样字符串）；
//   2. 「确认采购单」按详情响应的 `version` 作为 expected_version 提交，成功后重新拉详情；
//   3. 取消原因空白时前端拦下，不发请求；
//   4. 角色不足（VIEWER）时三个动作按钮都不渲染；
//   5. 填写取消原因后按 trim 后的原因提交。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import '../../tests/setupDom'

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: '30001' } }),
  useRouter: () => ({ push: vi.fn() }),
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

import {
  cancelPurchaseOrder,
  confirmPurchaseOrder,
  getPurchaseOrder,
} from '@/api/purchasing'
import type { PurchaseOrderDetail } from '@/types/purchasing'
import { useTeamStore } from '@/stores/team'
import PurchaseOrderDetailView from './PurchaseOrderDetail.vue'

function makeDetail(overrides: Partial<PurchaseOrderDetail> = {}): PurchaseOrderDetail {
  return {
    purchase_order_id: 30001,
    po_no: 'PO-20261021-0001',
    status: 'DRAFT',
    source: 'REPLENISHMENT',
    supplier_id: 9001,
    warehouse_id: 9004,
    expected_arrival_date: '2026-10-30',
    total_qty: '120.000',
    total_amount: '2400.00',
    received_qty: '0.000',
    remark: '首单',
    confirmed_by: null,
    confirmed_at: null,
    ordered_at: null,
    cancelled_at: null,
    cancel_reason: null,
    closed_at: null,
    idempotency_key: null,
    version: 7,
    created_at: '2026-10-21T02:00:00.000Z',
    items: [
      {
        id: 50001,
        line_no: 1,
        sku_id: 9002,
        product_id: 1,
        replenishment_item_id: 11,
        ordered_qty: '120.000',
        received_qty: '0.000',
        unit_price: '20.0000',
        amount: '2400.00',
        expected_arrival_date: '2026-10-30',
      },
    ],
    ...overrides,
  }
}

function mountDetail(role = 'ADMIN') {
  const pinia = createPinia()
  setActivePinia(pinia)
  useTeamStore().setRole(role)
  return mount(PurchaseOrderDetailView, { global: { plugins: [pinia, ElementPlus] } })
}

describe('PurchaseOrderDetail', () => {
  beforeEach(() => {
    vi.mocked(getPurchaseOrder).mockReset()
    vi.mocked(getPurchaseOrder).mockResolvedValue(makeDetail())
    vi.mocked(confirmPurchaseOrder).mockReset()
    vi.mocked(confirmPurchaseOrder).mockResolvedValue({
      purchase_order_id: 30001,
      po_no: 'PO-20261021-0001',
      status: 'PENDING',
      version: 8,
    })
    vi.mocked(cancelPurchaseOrder).mockReset()
    vi.mocked(cancelPurchaseOrder).mockResolvedValue({
      purchase_order_id: 30001,
      po_no: 'PO-20261021-0001',
      status: 'CANCELLED',
      cancel_reason: '供应商缺货',
      version: 8,
    })
  })

  afterEach(() => {
    vi.restoreAllMocks()
    document.body.innerHTML = ''
  })

  it('渲染头部（含创建时间）与采购明细，数量/金额不做前端换算', async () => {
    const wrapper = mountDetail()
    await flushPromises()

    expect(getPurchaseOrder).toHaveBeenCalledWith('30001')

    const text = wrapper.text()
    expect(text).toContain('PO-20261021-0001')
    expect(text).toContain('草稿')
    expect(text).toContain('创建时间')
    expect(text).toContain('120.000')
    expect(text).toContain('20.0000')
    expect(text).toContain('2400.00')

    const rows = wrapper.findAll('[data-testid="po-detail-items"] .el-table__body tbody tr')
    expect(rows.length).toBe(1)

    wrapper.unmount()
  })

  it('确认采购单以详情 version 作为 expected_version 提交，成功后重新拉详情', async () => {
    const wrapper = mountDetail('APPROVER')
    await flushPromises()

    await wrapper.find('[data-testid="po-action-confirm"]').trigger('click')
    await flushPromises()

    expect(confirmPurchaseOrder).toHaveBeenCalledTimes(1)
    expect(confirmPurchaseOrder).toHaveBeenCalledWith('30001', 7)
    // 首次加载 + 动作成功后各一次
    expect(getPurchaseOrder).toHaveBeenCalledTimes(2)

    wrapper.unmount()
  })

  it('取消原因为空白时前端拦下，不发取消请求', async () => {
    const warningSpy = vi.spyOn(ElMessage, 'warning')
    vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: '   ', action: 'confirm' } as never)

    const wrapper = mountDetail()
    await flushPromises()

    await wrapper.find('[data-testid="po-action-cancel"]').trigger('click')
    await flushPromises()

    expect(warningSpy).toHaveBeenCalledWith('请填写取消原因')
    expect(cancelPurchaseOrder).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('填写取消原因后按 trim 后的原因提交', async () => {
    vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({
      value: '  供应商缺货  ',
      action: 'confirm',
    } as never)

    const wrapper = mountDetail()
    await flushPromises()

    await wrapper.find('[data-testid="po-action-cancel"]').trigger('click')
    await flushPromises()

    expect(cancelPurchaseOrder).toHaveBeenCalledWith('30001', '供应商缺货')

    wrapper.unmount()
  })

  it('角色不足（VIEWER）时 confirm/order/cancel 按钮都不渲染', async () => {
    const wrapper = mountDetail('VIEWER')
    await flushPromises()

    expect(wrapper.find('[data-testid="po-action-confirm"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="po-action-order"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="po-action-cancel"]').exists()).toBe(false)

    wrapper.unmount()
  })
})
