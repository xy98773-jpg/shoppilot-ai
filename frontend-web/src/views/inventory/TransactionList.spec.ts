// 库存流水页：冲销按钮的角色显隐（`M3-B10` 验收点）。
//
// ★ 这是**体验层**断言：按钮隐藏不代表安全，后端冲销端点仍静态要求 OWNER/ADMIN
//   （角色不足 → TEAM-2003）；本用例只防「VIEWER/OPERATOR 看到不该看到的入口」。
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import '../../tests/setupDom'

vi.mock('@/api/inventory', () => ({
  listTransactions: vi.fn(),
  getTransaction: vi.fn(),
  reverseTransaction: vi.fn(),
  listBalances: vi.fn(),
  listAlerts: vi.fn(),
  adjustInventory: vi.fn(),
}))

import { listTransactions } from '@/api/inventory'
import { useTeamStore } from '@/stores/team'
import TransactionList from './TransactionList.vue'

const txnRow = {
  id: 42,
  warehouse_id: 9004,
  sku_id: 9002,
  sku_code: 'SKU-A',
  txn_type: 'COUNT_ADJUST' as const,
  biz_type: 'COUNT' as const,
  qty_delta: '-3.000',
  qty_before: '10.000',
  qty_after: '7.000',
  biz_ref_id: null,
  biz_ref_no: null,
  idempotency_key: 'seed-count-1',
  reason: '月度盘点',
  reversed_txn_id: null,
  operator_id: 1001,
  occurred_at: '2026-10-17T10:00:00.000Z',
  created_at: '2026-10-17T10:00:00.000Z',
}

async function mountAs(role: string) {
  const pinia = createPinia()
  setActivePinia(pinia)
  useTeamStore().setRole(role)
  const wrapper = mount(TransactionList, {
    global: { plugins: [pinia, ElementPlus] },
  })
  await flushPromises()
  return wrapper
}

/**
 * 是否出现「冲销」操作按钮。
 *
 * ★ 不能用 `wrapper.text().includes('冲销')`：详情弹窗里有「被冲销流水」字段，
 *   会让断言恒真；按钮文案精确等于「冲销」才是操作入口。
 */
function hasReverseButton(wrapper: ReturnType<typeof mount>): boolean {
  return wrapper
    .findAll('button')
    .some((btn) => btn.text().trim() === '冲销')
}

describe('TransactionList 冲销入口按角色显隐', () => {
  beforeEach(() => {
    vi.mocked(listTransactions).mockResolvedValue({
      list: [txnRow],
      page: 1,
      pageSize: 20,
      total: 1,
    })
  })

  it('VIEWER 看不到冲销按钮', async () => {
    const wrapper = await mountAs('VIEWER')
    expect(wrapper.text()).toContain('月度盘点') // 列表确实渲染出来了
    expect(hasReverseButton(wrapper)).toBe(false)
  })

  it('OPERATOR 看不到冲销按钮（后端要求 OWNER/ADMIN）', async () => {
    const wrapper = await mountAs('OPERATOR')
    expect(hasReverseButton(wrapper)).toBe(false)
  })

  it('OWNER 看得到冲销按钮', async () => {
    const wrapper = await mountAs('OWNER')
    expect(hasReverseButton(wrapper)).toBe(true)
  })

  it('ADMIN 看得到冲销按钮', async () => {
    const wrapper = await mountAs('ADMIN')
    expect(hasReverseButton(wrapper)).toBe(true)
  })
})
