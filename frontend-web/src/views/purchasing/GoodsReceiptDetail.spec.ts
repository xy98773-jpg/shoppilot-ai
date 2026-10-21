// 到货单详情页单测（M6-T11）。
//
// 覆盖：
//   1. 明细与头部渲染（数量/单价/金额原样字符串）；
//   2. 「确认到货」走 `postWithIdempotency`，第三个入参是带 `FE-GR-CONFIRM-<id>-<ts>` 的幂等键；
//   3. 确认成功后重新拉详情（不本地改状态充数）并提示成功；
//   4. `PO-9002`（超额）展示 `details[0].message`（含待收数量与本次登记数量）。
//
// ★ 本 spec 直接 mock `@/api/http`（而非 `@/api/purchasing`），
//   以便断言 api 层确实把幂等键交给了 `postWithIdempotency`。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import '../../tests/setupDom'

const { getMock, postMock, postWithIdempotencyMock } = vi.hoisted(() => ({
  getMock: vi.fn(),
  postMock: vi.fn(),
  postWithIdempotencyMock: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: '72001' } }),
  useRouter: () => ({ push: vi.fn() }),
}))

vi.mock('@/api/http', () => ({
  default: { get: getMock, post: postMock },
  postWithIdempotency: postWithIdempotencyMock,
}))

import type { GoodsReceiptDetail } from '@/types/purchasing'
import { useTeamStore } from '@/stores/team'
import GoodsReceiptDetailView from './GoodsReceiptDetail.vue'

function makeDetail(overrides: Partial<GoodsReceiptDetail> = {}): GoodsReceiptDetail {
  return {
    goods_receipt_id: 72001,
    receipt_no: 'GR-20261021-0001',
    purchase_order_id: 30001,
    warehouse_id: 9004,
    status: 'DRAFT',
    receipt_date: '2026-10-21',
    total_qty: '40.000',
    total_amount: '800.00',
    remark: '首批到货',
    version: 1,
    items: [
      {
        goods_receipt_item_id: 81001,
        purchase_order_item_id: 50001,
        sku_id: 9002,
        received_qty: '40.000',
        unit_price: '20.0000',
        amount: '800.00',
        batch_no: 'B-2026-10',
        remark: null,
      },
    ],
    created_at: '2026-10-21T03:00:00.000Z',
    updated_at: null,
    cancelled_at: null,
    confirmed_at: null,
    confirmed_by: null,
    ...overrides,
  }
}

function mountDetail(role = 'OPERATOR') {
  const pinia = createPinia()
  setActivePinia(pinia)
  useTeamStore().setRole(role)
  return mount(GoodsReceiptDetailView, { global: { plugins: [pinia, ElementPlus] } })
}

describe('GoodsReceiptDetail', () => {
  beforeEach(() => {
    getMock.mockReset()
    getMock.mockResolvedValue(makeDetail())
    postMock.mockReset()
    postWithIdempotencyMock.mockReset()
    postWithIdempotencyMock.mockResolvedValue({ data: makeDetail(), replayed: false })
  })

  afterEach(() => {
    vi.restoreAllMocks()
    document.body.innerHTML = ''
  })

  it('渲染头部与到货明细，数量/单价/金额原样展示', async () => {
    const wrapper = mountDetail()
    await flushPromises()

    expect(getMock).toHaveBeenCalledWith('/purchasing/receipts/72001')

    const text = wrapper.text()
    expect(text).toContain('GR-20261021-0001')
    expect(text).toContain('30001')
    expect(text).toContain('草稿')
    expect(text).toContain('40.000')
    expect(text).toContain('20.0000')
    expect(text).toContain('800.00')
    expect(text).toContain('B-2026-10')

    const rows = wrapper.findAll('[data-testid="gr-detail-items"] .el-table__body tbody tr')
    expect(rows.length).toBe(1)

    wrapper.unmount()
  })

  it('确认到货走 postWithIdempotency，并带 FE-GR-CONFIRM-<id>-<ts> 幂等键', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue({ action: 'confirm' } as never)

    const wrapper = mountDetail('OPERATOR')
    await flushPromises()

    await wrapper.find('[data-testid="gr-action-confirm"]').trigger('click')
    await flushPromises()

    expect(postWithIdempotencyMock).toHaveBeenCalledTimes(1)
    expect(postWithIdempotencyMock).toHaveBeenCalledWith(
      '/purchasing/receipts/72001/confirm',
      undefined,
      expect.stringMatching(/^FE-GR-CONFIRM-72001-\d+$/),
    )

    wrapper.unmount()
  })

  it('确认成功后提示成功并重新拉详情（不本地改状态充数）', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue({ action: 'confirm' } as never)
    const successSpy = vi.spyOn(ElMessage, 'success')
    getMock
      .mockResolvedValueOnce(makeDetail())
      .mockResolvedValueOnce(
        makeDetail({ status: 'CONFIRMED', confirmed_at: '2026-10-21T04:00:00.000Z', version: 2 }),
      )

    const wrapper = mountDetail('OWNER')
    await flushPromises()

    await wrapper.find('[data-testid="gr-action-confirm"]').trigger('click')
    await flushPromises()

    expect(successSpy).toHaveBeenCalledWith('到货已确认，库存已入库')
    expect(getMock).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('已确认')

    wrapper.unmount()
  })

  it('PO-9002 超额时展示 details[0].message（待收数量与本次登记）', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue({ action: 'confirm' } as never)
    const errorSpy = vi.spyOn(ElMessage, 'error')
    postWithIdempotencyMock.mockRejectedValue({
      code: 'PO-9002',
      message: '到货数量超过待收数量',
      details: [{ field: 'items[0].received_qty', message: '待收数量为 30.000，本次登记 40.000' }],
    })

    const wrapper = mountDetail('OPERATOR')
    await flushPromises()

    await wrapper.find('[data-testid="gr-action-confirm"]').trigger('click')
    await flushPromises()

    expect(errorSpy).toHaveBeenCalledWith('待收数量为 30.000，本次登记 40.000')

    wrapper.unmount()
  })

  it('角色不足（VIEWER）时不渲染确认/作废按钮', async () => {
    const wrapper = mountDetail('VIEWER')
    await flushPromises()

    expect(wrapper.find('[data-testid="gr-action-confirm"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="gr-action-cancel"]').exists()).toBe(false)

    wrapper.unmount()
  })
})
