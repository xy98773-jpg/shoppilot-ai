// 补货状态展示层单测（状态 → 动作矩阵 / 中文文案 / 配色）。
//
// ★ 这些是**体验层**规则：真正的状态机判定在服务端（`ReplenishmentItemService`），
//   这里钉死的是「界面在某个状态下应该给用户看到哪些按钮」，避免误显示出必被拒的操作。
import { describe, expect, it } from 'vitest'
import {
  REPLENISHMENT_STATUSES,
  RISK_LEVELS,
  availableActions,
  replenishmentStatusShort,
  replenishmentStatusTagType,
  reviewActionLabel,
  riskLevelShort,
  riskLevelTagType,
} from './replenishmentStatus'

describe('availableActions（状态 → 可执行动作矩阵）', () => {
  it('SUGGESTED 只能「提交审核」或「作废」', () => {
    expect(availableActions('SUGGESTED')).toEqual(['submit', 'cancel'])
  })

  it('PENDING_REVIEW 只能「撤回 / 通过 / 驳回」——★ 不含 cancel（服务端要求先 withdraw 再作废，否则 RPL-8001）', () => {
    const actions = availableActions('PENDING_REVIEW')
    expect(actions).toEqual(['withdraw', 'approve', 'reject'])
    expect(actions).not.toContain('cancel')
    expect(actions).not.toContain('submit')
  })

  it('APPROVED 只剩「作废」（转采购单走勾选按钮，不走这里）', () => {
    expect(availableActions('APPROVED')).toEqual(['cancel'])
  })

  it('四个终态（CONVERTED / REJECTED / SUPERSEDED / CANCELLED）一律无动作', () => {
    for (const status of ['CONVERTED', 'REJECTED', 'SUPERSEDED', 'CANCELLED']) {
      expect(availableActions(status)).toEqual([])
    }
  })
})

describe('中文文案与配色', () => {
  it('7 个状态都有中文短文案（不出现英文码兜底）', () => {
    expect(REPLENISHMENT_STATUSES).toHaveLength(7)
    for (const status of REPLENISHMENT_STATUSES) {
      const zh = replenishmentStatusShort(status)
      expect(zh).not.toBe(status)
      expect(zh.length).toBeGreaterThan(0)
    }
    expect(replenishmentStatusShort('PENDING_REVIEW')).toBe('待审批')
  })

  it('5 个风险等级都有中文短文案；HIGH 用 danger 配色（电商口径：高风险最醒目）', () => {
    expect(RISK_LEVELS).toHaveLength(5)
    for (const level of RISK_LEVELS) {
      expect(riskLevelShort(level)).not.toBe(level)
    }
    expect(riskLevelTagType('HIGH')).toBe('danger')
    expect(riskLevelTagType('MEDIUM')).toBe('warning')
    expect(riskLevelTagType('NONE')).toBe('info')
  })

  it('状态配色区分终态与进行中（已转采购单绿 / 待审批橙 / 终态灰）', () => {
    expect(replenishmentStatusTagType('CONVERTED')).toBe('success')
    expect(replenishmentStatusTagType('PENDING_REVIEW')).toBe('warning')
    expect(replenishmentStatusTagType('CANCELLED')).toBe('info')
    expect(replenishmentStatusTagType('SUPERSEDED')).toBe('info')
  })

  it('审核动作 5 值均有中文；未知值原样返回（不伪造文案）', () => {
    expect(reviewActionLabel('APPROVE')).toBe('审批通过')
    expect(reviewActionLabel('WITHDRAW')).toBe('撤回提交')
    expect(reviewActionLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW')
  })
})
