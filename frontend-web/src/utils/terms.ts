// 术语中文化：所有对外展示的英文枚举统一走这里，格式固定为「英文代码（中文）」。
//
// 例：HIGH（高风险）、STOCKOUT（缺货）、Q_base（基础补货量）
//
// ★ 只做展示层翻译，不改变提交给后端/契约的取值（`value` 永远传英文代码）。
// ★ 枚举全集与代码同源：
//   - 风险等级/风险类型/数据质量/LLM 状态：`service-ai/.../schemas/analysis_response.py`
//   - 范围类型：`AnalysisScopeType`；交期来源：`LeadTimeSourceValue`
import type { AnalysisScopeType } from '@/types/analysis'

/** 通用映射表：英文枚举 → 中文释义。 */
export type TermMap = Record<string, string>

/** 风险等级（`RiskLevel`）。 */
export const RISK_LEVEL_TERMS: TermMap = {
  HIGH: '高风险',
  MEDIUM: '中风险',
  LOW: '低风险',
  NONE: '无风险',
  UNKNOWN: '未知',
}

/** 风险类型（六条规则的结果标签）。 */
export const RISK_TYPE_TERMS: TermMap = {
  STOCKOUT: '缺货',
  LOW_STOCK: '低库存',
  OVERSTOCK: '库存积压',
  SLOW_MOVING: '滞销',
  SALES_ANOMALY: '销售异常',
  DATA_INSUFFICIENT: '数据不足',
}

/** 分析范围类型（`AnalysisScopeType`）。 */
export const SCOPE_TYPE_TERMS: TermMap = {
  ALL_ACTIVE: '全部活跃 SKU',
  SELECTED_SKUS: '指定 SKU',
  CATEGORY: '按类目',
}

/** 单 SKU 的 LLM 解释状态（`LlmStatus`）。 */
export const LLM_STATUS_TERMS: TermMap = {
  NOT_REQUESTED: '未请求',
  PENDING: '待生成',
  SUCCEEDED: '已生成',
  FAILED: '生成失败',
  TIMEOUT: '生成超时',
  VALIDATION_FAILED: '校验未通过',
  SKIPPED: '已跳过',
}

/** 数据质量分档（`DataQuality`）。 */
export const DATA_QUALITY_TERMS: TermMap = {
  GOOD: '充足',
  FAIR: '一般',
  POOR: '偏差',
  INSUFFICIENT: '数据不足',
}

/** 交期来源（`LeadTimeSourceValue`）：这个交期天数是按哪一级配置取的。 */
export const LEAD_TIME_SOURCE_TERMS: TermMap = {
  POLICY: '补货策略配置',
  SKU: 'SKU 自身配置',
  SUPPLIER: '供应商配置',
  DEFAULT: '全局默认值',
}

/** 库存流水类型（`inventory_transactions.txn_type`）。 */
export const TXN_TYPE_TERMS: TermMap = {
  INIT_IMPORT: '期初导入',
  PURCHASE_INBOUND: '采购入库',
  SALE_OUTBOUND: '销售出库',
  COUNT_ADJUST: '盘点调整',
  MANUAL_ADJUST: '手工调整',
  REVERSAL: '冲销',
}

/** 流水的业务来源（`inventory_transactions.biz_type`）。 */
export const BIZ_TYPE_TERMS: TermMap = {
  IMPORT: '导入',
  MANUAL: '手工录入',
  SALE: '销售',
  PURCHASE: '采购',
  ADJUST: '盘点',
  REVERSAL: '冲销',
}

/** 销售数据的写入来源（`sales_daily.source`）。 */
export const SOURCE_TERMS: TermMap = {
  IMPORT: '导入',
  MANUAL: '手工录入',
  API: '接口同步',
}

/** 补货建议状态（`ReplenishmentItemStatus`）。 */
export const REPLENISHMENT_STATUS_TERMS: TermMap = {
  SUGGESTED: '待提交',
  PENDING_REVIEW: '待审批',
  APPROVED: '已审批（可转采购单）',
  CONVERTED: '已转采购单',
  REJECTED: '已驳回',
  SUPERSEDED: '已被新建议取代',
  CANCELLED: '已取消',
}

/** 统一格式：`CODE（中文）`；未收录的代码原样返回，避免出现 "undefined"。 */
export function withZh(code: string | null | undefined, map: TermMap): string {
  if (!code) return '-'
  const zh = map[code]
  return zh ? `${code}（${zh}）` : code
}

export function riskLevelLabel(code?: string | null): string {
  return withZh(code, RISK_LEVEL_TERMS)
}

export function riskTypeLabel(code?: string | null): string {
  return withZh(code, RISK_TYPE_TERMS)
}

/** 风险类型数组 → `STOCKOUT（缺货） / LOW_STOCK（低库存）`。 */
export function riskTypesLabel(codes?: readonly string[] | null): string {
  if (!codes || codes.length === 0) return '-'
  return codes.map((code) => withZh(code, RISK_TYPE_TERMS)).join(' / ')
}

export function scopeTypeLabel(code?: AnalysisScopeType | string | null): string {
  return withZh(code, SCOPE_TYPE_TERMS)
}

export function llmStatusLabel(code?: string | null): string {
  return withZh(code, LLM_STATUS_TERMS)
}

export function dataQualityLabel(code?: string | null): string {
  return withZh(code, DATA_QUALITY_TERMS)
}

export function leadTimeSourceLabel(code?: string | null): string {
  return withZh(code, LEAD_TIME_SOURCE_TERMS)
}

export function txnTypeLabel(code?: string | null): string {
  return withZh(code, TXN_TYPE_TERMS)
}

export function bizTypeLabel(code?: string | null): string {
  return withZh(code, BIZ_TYPE_TERMS)
}

export function sourceLabel(code?: string | null): string {
  return withZh(code, SOURCE_TERMS)
}

export function replenishmentStatusLabel(code?: string | null): string {
  return withZh(code, REPLENISHMENT_STATUS_TERMS)
}

/** 结果页底部的术语速查（只列会被前端展示的六类风险）。 */
export const RISK_TYPE_LEGEND = Object.entries(RISK_TYPE_TERMS)
  .map(([code, zh]) => `${code}（${zh}）`)
  .join(' · ')
