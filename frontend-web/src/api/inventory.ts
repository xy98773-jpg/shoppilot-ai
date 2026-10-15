// 库存模块（骨架占位）。
import http from './http'
import type { PageQuery, PageResult } from '@/types/common'
import type { InventoryRecord } from '@/types/inventory'

// TODO: 库存概览、流水、盘点相关接口。
export function listInventory(query: PageQuery): Promise<PageResult<InventoryRecord>> {
  return http.get<unknown, PageResult<InventoryRecord>>('/inventory/records', { params: query })
}
