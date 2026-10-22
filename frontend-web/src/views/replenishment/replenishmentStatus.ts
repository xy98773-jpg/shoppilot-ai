// 补货模块状态文案与配色（colocated 纯函数，供补货各页复用）。
//
// ★ 状态机权威在服务端（`ReplenishmentItemService`）；本文件只做展示层文案与 el-tag 配色，
//   并按当前状态计算「哪些按钮该显示」——这仅是**体验层**，服务端仍是唯一安全边界。
// ★ 术语格式统一「英文代码（中文）」：中文释义唯一来源是 `@/utils/terms`，
//   本文件只补「同一术语的纯中文短语」（用于 el-tag 等空间紧张处），避免两处翻译漂移。
import type { ReplenishmentStatus } from '@/types/replenishment'
import { REPLENISHMENT_STATUS_TERMS, RISK_LEVEL_TERMS } from '@/utils/terms'

/** 状态选项（筛选下拉与展示共用；顺序即状态机顺序）。 */
export const REPLENISHMENT_STATUSES: ReplenishmentStatus[] = [
  'SUGGESTED',
  'PENDING_REVIEW',
  'APPROVED',
  'CONVERTED',
  'REJECTED',
  'SUPERSEDED',
  'CANCELLED',
]

/** 风险等级选项（服务端筛选白名单 5 值）。 */
export const RISK_LEVELS = ['HIGH', 'MEDIUM', 'LOW', 'NONE', 'UNKNOWN'] as const

/** 状态中文短文案（el-tag 用；与 `REPLENISHMENT_STATUS_TERMS` 同源，去掉括号外的英文码）。 */
export function replenishmentStatusShort(status: string): string {
  return REPLENISHMENT_STATUS_TERMS[status] ?? status
}

/** 风险等级中文短文案（el-tag 用）。 */
export function riskLevelShort(level: string): string {
  return RISK_LEVEL_TERMS[level] ?? level
}

/**
 * 风险等级 el-tag 类型：高红 / 中橙 / 低蓝 / 无或未知灰。
 *
 * ★ 电商口径：高风险用 `danger`（红色）最醒目，与服务端 `HIGH` 语义一致。
 */
export function riskLevelTagType(level: string): 'danger' | 'warning' | 'primary' | 'info' {
  if (level === 'HIGH') return 'danger'
  if (level === 'MEDIUM') return 'warning'
  if (level === 'LOW') return 'primary'
  return 'info'
}

/** 状态 el-tag 类型：已转采购单绿 / 已审批绿蓝 / 终态灰 / 待审批橙 / 待提交蓝。 */
export function replenishmentStatusTagType(
  status: string,
): 'success' | 'info' | 'warning' | 'primary' | 'danger' {
  if (status === 'CONVERTED') return 'success'
  if (status === 'APPROVED') return 'primary'
  if (status === 'REJECTED') return 'danger'
  if (status === 'CANCELLED' || status === 'SUPERSEDED') return 'info'
  if (status === 'PENDING_REVIEW') return 'warning'
  return 'primary'
}

/** 审核动作中文（时间线用；`ReviewAction` 5 值）。 */
export const REVIEW_ACTION_TERMS: Record<string, string> = {
  SUBMIT: '提交审核',
  WITHDRAW: '撤回提交',
  APPROVE: '审批通过',
  REJECT: '驳回',
  CANCEL: '作废',
}

export function reviewActionLabel(action: string): string {
  return REVIEW_ACTION_TERMS[action] ?? action
}

/**
 * 某状态下可执行的动作（**体验层**判定；真正的合法性由服务端状态机裁决）。
 *
 * - `SUGGESTED`      → submit / cancel
 * - `PENDING_REVIEW` → withdraw / approve / reject
 * - `APPROVED`       → convert（转采购单）/ cancel
 * - 终态（CONVERTED / REJECTED / SUPERSEDED / CANCELLED）→ 无
 */
export function availableActions(
  status: string,
): Array<'submit' | 'withdraw' | 'approve' | 'reject' | 'cancel'> {
  if (status === 'SUGGESTED') return ['submit', 'cancel']
  if (status === 'PENDING_REVIEW') return ['withdraw', 'approve', 'reject']
  if (status === 'APPROVED') return ['cancel']
  return []
}
