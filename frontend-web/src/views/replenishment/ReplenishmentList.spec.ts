// 补货建议页单测（M8-R200）。
//
// 覆盖：
//   1. 默认分页拉取 `GET /replenishment/items` 并渲染（数量/单价原样字符串，状态中文化）；
//   2. `补货审核` 路由模式下默认筛「待审批」；
//   3. 提交审核 → 调 `POST .../submit` 并刷新列表；
//   4. 审批通过 → 带**行上的 version** 作为 `expected_version`；
//   5. 跨仓库勾选 → 前端拦下、不发请求；
//   6. 正常转采购单 → `POST /purchasing/orders/from-replenishment` 参数正确；
//   7. `RPL-8012` 冲突 → 用户确认后带 `conflict_acknowledged` + `acknowledged_item_ids` 重试，
//      且**复用同一个幂等键**（同一用户意图）。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import '../../tests/setupDom'

const { pushMock, routeRef } = vi.hoisted(() => ({
  pushMock: vi.fn(),
  routeRef: { name: 'replenishment-suggestions' } as { name: string },
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: pushMock }),
  useRoute: () => routeRef,
}))

vi.mock('@/api/replenishment', () => ({
  listReplenishmentItems: vi.fn(),
  getReplenishmentItem: vi.fn(),
  listReplenishmentReviews: vi.fn(),
  updateReplenishmentItem: vi.fn(),
  submitReplenishment: vi.fn(),
  withdrawReplenishment: vi.fn(),
  approveReplenishment: vi.fn(),
  rejectReplenishment: vi.fn(),
  cancelReplenishment: vi.fn(),
}))

vi.mock('@/api/purchasing', () => ({
  createPurchaseOrderFromReplenishment: vi.fn(),
}))

import {
  approveReplenishment,
  listReplenishmentItems,
  submitReplenishment,
} from '@/api/replenishment'
import { createPurchaseOrderFromReplenishment } from '@/api/purchasing'
import { useTeamStore } from '@/stores/team'
import type { ReplenishmentItem } from '@/types/replenishment'
import ReplenishmentList from './ReplenishmentList.vue'

function makeItem(overrides: Partial<ReplenishmentItem> = {}): ReplenishmentItem {
  return {
    id: 501,
    source_analysis_item_id: 9001,
    run_id: 18,
    sku_id: 910001,
    product_id: 1,
    warehouse_id: 9003,
    status: 'SUGGESTED',
    suggested_qty: '80.000',
    qty: '80.000',
    risk_level: 'HIGH',
    supplier_id: 1,
    purchase_price: '12.3456',
    expected_arrival_date: null,
    created_at: '2026-10-21T03:00:00.000Z',
    version: 1,
    ...overrides,
  }
}

function pageResult(list: ReplenishmentItem[], total = list.length) {
  return { list, page: 1, pageSize: 20, total }
}

function mountPage() {
  const pinia = createPinia()
  setActivePinia(pinia)
  return mount(ReplenishmentList, { global: { plugins: [pinia, ElementPlus] } })
}

/** 弹窗内容：Element Plus 的 `el-dialog` 默认 `append-to-body=false`，故仍留在组件树内。 */
function dialogEl(wrapper: ReturnType<typeof mountPage>, testid: string): HTMLElement | null {
  const found = wrapper.find(`[data-testid="${testid}"]`)
  return found.exists() ? (found.element as HTMLElement) : null
}

async function clickInDialog(wrapper: ReturnType<typeof mountPage>, testid: string): Promise<void> {
  const el = dialogEl(wrapper, testid)
  expect(el, `未找到 [data-testid=${testid}]`).not.toBeNull()
  el?.click()
  await flushPromises()
}

describe('ReplenishmentList', () => {
  beforeEach(() => {
    routeRef.name = 'replenishment-suggestions'
    pushMock.mockReset()
    vi.mocked(listReplenishmentItems).mockReset()
    vi.mocked(listReplenishmentItems).mockResolvedValue(pageResult([makeItem()]))
    vi.mocked(submitReplenishment).mockReset()
    vi.mocked(approveReplenishment).mockReset()
    vi.mocked(createPurchaseOrderFromReplenishment).mockReset()
  })

  afterEach(() => {
    vi.restoreAllMocks()
    document.body.innerHTML = ''
  })

  it('首次加载按默认分页请求，数量/单价保持后端字符串原样（口径铁律 ④）', async () => {
    const wrapper = mountPage()
    await flushPromises()

    expect(vi.mocked(listReplenishmentItems).mock.calls[0][0]).toEqual({ page: 1, pageSize: 20 })

    const text = wrapper.text()
    expect(text).toContain('910001')
    expect(text).toContain('80.000')
    expect(text).toContain('12.3456')
    // 状态与风险都中文化（英文码 + 中文释义），不出现裸 HIGH
    expect(text).toContain('HIGH（高风险）')
    expect(text).toContain('待提交')

    wrapper.unmount()
  })

  it('「补货审核」路由模式默认只查待审批（PENDING_REVIEW）', async () => {
    routeRef.name = 'replenishment-review'
    const wrapper = mountPage()
    await flushPromises()

    expect(vi.mocked(listReplenishmentItems).mock.calls[0][0]).toEqual({
      page: 1,
      pageSize: 20,
      status: 'PENDING_REVIEW',
    })
    expect(wrapper.text()).toContain('补货审核（待审批队列）')

    wrapper.unmount()
  })

  it('提交审核调用 submit 并刷新列表（不本地改状态充数）', async () => {
    const wrapper = mountPage()
    await flushPromises()
    useTeamStore().setRole('OPERATOR')
    await flushPromises()

    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    vi.mocked(submitReplenishment).mockResolvedValue({
      replenishment_item_id: 501,
      status: 'PENDING_REVIEW',
      qty: '80.000',
      version: 2,
      review_id: 77,
      action: 'SUBMIT',
      from_status: 'SUGGESTED',
      to_status: 'PENDING_REVIEW',
    })

    await wrapper.find('[data-testid="rpl-row-submit"]').trigger('click')
    await flushPromises()

    expect(submitReplenishment).toHaveBeenCalledWith(501)
    expect(listReplenishmentItems).toHaveBeenCalledTimes(2)

    wrapper.unmount()
  })

  it('审批通过带上行上的 version 作为 expected_version（乐观锁令牌）', async () => {
    vi.mocked(listReplenishmentItems).mockResolvedValue(
      pageResult([makeItem({ status: 'PENDING_REVIEW', version: 3 })]),
    )
    const wrapper = mountPage()
    await flushPromises()
    useTeamStore().setRole('APPROVER')
    await flushPromises()

    vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: '同意补货', action: 'confirm' } as never)
    vi.mocked(approveReplenishment).mockResolvedValue({
      replenishment_item_id: 501,
      status: 'APPROVED',
      qty: '80.000',
      version: 4,
      review_id: 78,
      action: 'APPROVE',
      from_status: 'PENDING_REVIEW',
      to_status: 'APPROVED',
    })

    await wrapper.find('[data-testid="rpl-row-approve"]').trigger('click')
    await flushPromises()

    expect(approveReplenishment).toHaveBeenCalledWith(501, 3, '同意补货')

    wrapper.unmount()
  })

  it('勾选跨仓库的建议 → 前端拦下并提示，不发请求（一张采购单只能一个到货仓）', async () => {
    vi.mocked(listReplenishmentItems).mockResolvedValue(
      pageResult([
        makeItem({ id: 601, status: 'APPROVED', warehouse_id: 9003 }),
        makeItem({ id: 602, status: 'APPROVED', warehouse_id: 9004 }),
      ]),
    )
    const wrapper = mountPage()
    await flushPromises()
    useTeamStore().setRole('APPROVER')
    await flushPromises()

    wrapper
      .findAllComponents({ name: 'ElTable' })[0]
      .vm.$emit('selection-change', [
        makeItem({ id: 601, status: 'APPROVED', warehouse_id: 9003 }),
        makeItem({ id: 602, status: 'APPROVED', warehouse_id: 9004 }),
      ])
    await flushPromises()

    await wrapper.find('[data-testid="rpl-convert-open"]').trigger('click')
    await flushPromises()

    expect(createPurchaseOrderFromReplenishment).not.toHaveBeenCalled()
    expect(dialogEl(wrapper, 'rpl-convert-submit')).toBeNull()

    wrapper.unmount()
  })

  it('正常转采购单：提交所选 ID + 仓库 + 期望到货日，成功后跳采购单详情', async () => {
    vi.mocked(listReplenishmentItems).mockResolvedValue(
      pageResult([makeItem({ id: 601, status: 'APPROVED' })]),
    )
    const wrapper = mountPage()
    await flushPromises()
    useTeamStore().setRole('APPROVER')
    await flushPromises()

    vi.mocked(createPurchaseOrderFromReplenishment).mockResolvedValue({
      data: {
        purchase_order_id: 31001,
        po_no: 'PO-20261022-0001',
        status: 'DRAFT',
        source: 'REPLENISHMENT',
        supplier_id: 1,
        warehouse_id: 9003,
        expected_arrival_date: null,
        total_qty: '80.000',
        total_amount: '987.65',
        items: [],
      },
      replayed: false,
    })

    wrapper
      .findAllComponents({ name: 'ElTable' })[0]
      .vm.$emit('selection-change', [makeItem({ id: 601, status: 'APPROVED' })])
    await flushPromises()

    await wrapper.find('[data-testid="rpl-convert-open"]').trigger('click')
    await flushPromises()
    await clickInDialog(wrapper, 'rpl-convert-submit')

    const payload = vi.mocked(createPurchaseOrderFromReplenishment).mock.calls[0][0]
    expect(payload).toEqual({
      replenishment_item_ids: [601],
      warehouse_id: 9003,
      expected_arrival_date: null,
      remark: null,
    })
    expect(vi.mocked(createPurchaseOrderFromReplenishment).mock.calls[0][1]).toMatch(/.+/)
    expect(pushMock).toHaveBeenCalledWith({
      name: 'purchasing-order-detail',
      params: { id: '31001' },
    })

    wrapper.unmount()
  })

  it('RPL-8012 冲突：确认后带确认集重试，且复用同一幂等键（防重复建单）', async () => {
    vi.mocked(listReplenishmentItems).mockResolvedValue(
      pageResult([makeItem({ id: 601, status: 'APPROVED' })]),
    )
    const wrapper = mountPage()
    await flushPromises()
    useTeamStore().setRole('APPROVER')
    await flushPromises()

    const conflict = {
      code: 'RPL-8012',
      message: '该 SKU 在本仓库还有其它已审批的补货建议，请确认是否继续',
      details: [
        {
          field: 'conflict_items',
          message: '该 SKU 在本仓库还有其它已审批的补货建议',
          rejectedValue: {
            replenishment_item_id: 507,
            sku_id: 910001,
            qty: '60.000',
            risk_level: 'HIGH',
            created_at: '2026-10-21T01:00:00.000Z',
          },
        },
      ],
    }
    vi.mocked(createPurchaseOrderFromReplenishment)
      .mockRejectedValueOnce(conflict)
      .mockResolvedValueOnce({
        data: {
          purchase_order_id: 31002,
          po_no: 'PO-20261022-0002',
          status: 'DRAFT',
          source: 'REPLENISHMENT',
          supplier_id: 1,
          warehouse_id: 9003,
          expected_arrival_date: null,
          total_qty: '80.000',
          total_amount: '987.65',
          items: [],
        },
        replayed: false,
      })

    // 第一次 409 不弹窗；第二次是冲突确认框（dangerouslyUseHTMLString）
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)

    wrapper
      .findAllComponents({ name: 'ElTable' })[0]
      .vm.$emit('selection-change', [makeItem({ id: 601, status: 'APPROVED' })])
    await flushPromises()

    await wrapper.find('[data-testid="rpl-convert-open"]').trigger('click')
    await flushPromises()
    await clickInDialog(wrapper, 'rpl-convert-submit')
    await flushPromises()

    const calls = vi.mocked(createPurchaseOrderFromReplenishment).mock.calls
    expect(calls).toHaveLength(2)
    // 第二次带上确认标记 + 服务端回传的冲突 ID 集合
    expect(calls[1][0]).toEqual({
      replenishment_item_ids: [601],
      warehouse_id: 9003,
      expected_arrival_date: null,
      remark: null,
      conflict_acknowledged: true,
      acknowledged_item_ids: [507],
    })
    // ★ 同一用户意图 ⇒ 幂等键必须相同
    expect(calls[1][1]).toBe(calls[0][1])

    wrapper.unmount()
  })
})
