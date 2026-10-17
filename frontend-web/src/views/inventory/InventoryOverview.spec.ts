// 库存总览组件测试（`M3-B10` 验收点：四列分列渲染 + 口径说明文案存在）。
//
// ★ 本用例的**核心断言**是「四个数量列各自独立」以及「口径说明必须可见」——
//   后者是 R67/R77 的落地证据：`available_qty` 不含在途、不是补货口径 IP。
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia } from 'pinia'
import '../../tests/setupDom'

vi.mock('@/api/inventory', () => ({
  listBalances: vi.fn(),
  listAlerts: vi.fn(),
  listTransactions: vi.fn(),
  getTransaction: vi.fn(),
  adjustInventory: vi.fn(),
  reverseTransaction: vi.fn(),
}))

import { listAlerts, listBalances } from '@/api/inventory'
import InventoryOverview from './InventoryOverview.vue'

const balanceRow = {
  id: 1,
  warehouse_id: 9004,
  sku_id: 9002,
  sku_code: 'SKU-A',
  on_hand_qty: '10.000',
  in_transit_qty: '4.000',
  committed_qty: '3.000',
  available_qty: '7.000',
  last_txn_at: '2026-10-17T10:00:00.000Z',
  version: 2,
}

describe('InventoryOverview', () => {
  beforeEach(() => {
    vi.mocked(listBalances).mockResolvedValue({
      list: [balanceRow],
      page: 1,
      pageSize: 20,
      total: 1,
    })
    vi.mocked(listAlerts).mockResolvedValue({
      list: [
        {
          warehouse_id: 9004,
          sku_id: 9002,
          sku_code: 'SKU-A',
          alert_type: 'LOWSTOCK',
          on_hand_qty: '10.000',
          available_qty: '7.000',
          threshold: '8.000',
          message: '可用量低于安全库存',
        },
      ],
      page: 1,
      pageSize: 20,
      total: 1,
    })
  })

  it('四个数量列分列渲染，且明确写出 available_qty 不是补货口径 IP', async () => {
    const wrapper = mount(InventoryOverview, {
      global: { plugins: [ElementPlus, createPinia()] },
    })
    await flushPromises()

    const text = wrapper.text()

    // 1) 四列各自独立（列头文案逐字存在）
    expect(text).toContain('现有库存 on_hand')
    expect(text).toContain('原始在途 in_transit（非 IP）')
    expect(text).toContain('已承诺 committed')
    expect(text).toContain('可用量 available（不含在途）')

    // 2) 口径说明文案（验收点）
    expect(text).toContain('available_qty = on_hand_qty − committed_qty')
    expect(text).toContain('不是补货口径 IP')
    expect(text).toContain('前端不得用 on_hand + in_transit − committed 自算 IP')

    // 3) 数量原样展示字符串（不做精确算术、不转数值）
    expect(text).toContain('10.000')
    expect(text).toContain('4.000')
    expect(text).toContain('3.000')
    expect(text).toContain('7.000')

    // 4) 预警区渲染服务端给的阈值与提示（前端不重算）
    expect(text).toContain('LOWSTOCK')
    expect(text).toContain('8.000')
    expect(text).toContain('可用量低于安全库存')
  })

  it('不渲染任何伪 IP 列（禁止 on_hand + in_transit − committed 的结果）', async () => {
    const wrapper = mount(InventoryOverview, {
      global: { plugins: [ElementPlus, createPinia()] },
    })
    await flushPromises()

    // 10 + 4 − 3 = 11；该数字不应由前端算出并展示
    expect(wrapper.text()).not.toContain('11.000')
    // 也不应出现 "IP" 作为独立列名（只在「非 IP」「不是补货口径 IP」等否定语境出现）
    expect(wrapper.text()).not.toMatch(/IP（?补货/)
  })
})
