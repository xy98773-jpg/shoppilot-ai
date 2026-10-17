// 导入批次列表的查询参数装配（纯函数，无副作用）。
//
// ★ 只做「控件值 → 请求参数」的搬运：剔除空值、把字符串转成后端要的数值类型，
//   不做任何业务判断（状态机、可见性由服务端决定）。
// ★ 独立成模块的原因：`<script setup>` 内不能 export，而这些转换必须被单测直接覆盖
//   （错误分页 / 导出参数拼接是本批验收点之一）。
import type {
  ImportBatchQuery,
  ImportErrorExportQuery,
  ImportErrorQuery,
} from '@/types/importdata'

/** 批次列表筛选控件的原始状态（全部为字符串，来自输入框/选择器）。 */
export interface BatchFilterInput {
  status?: string
  bizType?: string
  from?: string
  to?: string
}

/** 去掉空串 / undefined（`from` / `to` 为空时不传，避免后端按空串解析日期）。 */
export function pruneFilter(input: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(input)) {
    if (value !== '' && value !== null && value !== undefined) {
      out[key] = value
    }
  }
  return out
}

/** 批次列表查询（`page` 从 1 起，`pageSize` 上限 200 由后端校验）。 */
export function buildBatchQuery(
  input: BatchFilterInput,
  page: number,
  pageSize: number,
): ImportBatchQuery {
  const pruned = pruneFilter({
    status: input.status,
    bizType: input.bizType,
    from: input.from,
    to: input.to,
  })
  return { page, pageSize, ...pruned } as ImportBatchQuery
}

/** 错误明细分页查询：`errorLevel` / `rowNo` 为空即不传（后端按「全部」处理）。 */
export function buildErrorQuery(
  page: number,
  pageSize: number,
  errorLevel?: string,
  rowNo?: string | number,
): ImportErrorQuery {
  const parsedRowNo = toRowNo(rowNo)
  const query: ImportErrorQuery = { page, pageSize }
  if (errorLevel) {
    query.errorLevel = errorLevel
  }
  if (parsedRowNo !== undefined) {
    query.rowNo = parsedRowNo
  }
  return query
}

/**
 * 错误导出参数（无分页）。
 *
 * ★ 必须与 `GET /{id}/errors` 的筛选语义一致（导出「当前筛选结果」而不是全量），
 *   故复用同一套空值剔除与行号解析。
 */
export function buildErrorExportParams(
  errorLevel?: string,
  rowNo?: string | number,
): ImportErrorExportQuery {
  const parsedRowNo = toRowNo(rowNo)
  const params: ImportErrorExportQuery = {}
  if (errorLevel) {
    params.errorLevel = errorLevel
  }
  if (parsedRowNo !== undefined) {
    params.rowNo = parsedRowNo
  }
  return params
}

/** 行号：空 → undefined；非数字 / 非正数 → undefined（不把脏值发给后端）。 */
function toRowNo(value?: string | number): number | undefined {
  if (value === undefined || value === null || value === '') {
    return undefined
  }
  const n = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(n) && n > 0 ? Math.trunc(n) : undefined
}
