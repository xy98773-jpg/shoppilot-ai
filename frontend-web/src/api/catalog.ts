// 商品/目录模块（骨架占位）。
import http from './http'
import type { PageQuery, PageResult } from '@/types/common'
import type { Sku } from '@/types/inventory'

// TODO: 商品、SKU、供应商、仓库相关接口。
export function listSkus(query: PageQuery): Promise<PageResult<Sku>> {
  return http.get<unknown, PageResult<Sku>>('/catalog/skus', { params: query })
}
