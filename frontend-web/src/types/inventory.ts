// 库存相关类型（占位，纯类型）。

export interface Sku {
  id: string
  skuCode: string
  name: string
}

export interface InventoryRecord {
  id: string
  skuId: string
  warehouseId: string
  quantity: number
}
