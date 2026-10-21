// 采购模块状态文案与配色（colocated 纯函数，供采购四页复用）。
//
// ★ 两个值空间**互相独立**，不得互串：
//   - 采购单 6 值（`PurchaseOrderStatus`）：DRAFT/PENDING/ORDERED/PARTIALLY_RECEIVED/RECEIVED/CANCELLED
//   - 到货单 4 值（`GoodsReceiptStatus`）：DRAFT/CONFIRMED/REVERSED/CANCELLED
// ★ 服务端是状态机的唯一判定方；本文件只做展示层文案与 el-tag 配色。
import type { GoodsReceiptStatus, PurchaseOrderStatus } from '@/types/purchasing'

/** 采购单状态选项（筛选下拉与展示共用，顺序即状态机顺序）。 */
export const PO_STATUSES: PurchaseOrderStatus[] = [
  'DRAFT',
  'PENDING',
  'ORDERED',
  'PARTIALLY_RECEIVED',
  'RECEIVED',
  'CANCELLED',
]

/** 到货单状态选项。 */
export const GR_STATUSES: GoodsReceiptStatus[] = ['DRAFT', 'CONFIRMED', 'REVERSED', 'CANCELLED']

const PO_STATUS_LABELS: Record<PurchaseOrderStatus, string> = {
  DRAFT: '草稿',
  PENDING: '待采购',
  ORDERED: '采购中',
  PARTIALLY_RECEIVED: '部分到货',
  RECEIVED: '已到货',
  CANCELLED: '已取消',
}

const GR_STATUS_LABELS: Record<GoodsReceiptStatus, string> = {
  DRAFT: '草稿',
  CONFIRMED: '已确认',
  REVERSED: '已冲销',
  CANCELLED: '已作废',
}

/** 采购单状态中文文案（未知值原样返回，不伪造）。 */
export function poStatusLabel(status: string): string {
  return PO_STATUS_LABELS[status as PurchaseOrderStatus] ?? status
}

/** 到货单状态中文文案。 */
export function grStatusLabel(status: string): string {
  return GR_STATUS_LABELS[status as GoodsReceiptStatus] ?? status
}

/** el-tag 类型：已到货绿 / 已取消灰 / 部分到货橙 / 其余蓝。 */
export function poStatusTagType(status: string): 'success' | 'info' | 'warning' | 'primary' {
  if (status === 'RECEIVED') return 'success'
  if (status === 'CANCELLED') return 'info'
  if (status === 'PARTIALLY_RECEIVED') return 'warning'
  return 'primary'
}

/** el-tag 类型：已确认绿 / 已作废或已冲销灰 / 草稿蓝。 */
export function grStatusTagType(status: string): 'success' | 'info' | 'warning' | 'primary' {
  if (status === 'CONFIRMED') return 'success'
  if (status === 'CANCELLED' || status === 'REVERSED') return 'info'
  return 'primary'
}
