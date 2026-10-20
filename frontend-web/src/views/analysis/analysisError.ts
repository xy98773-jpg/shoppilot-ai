// 分析模块错误分类（纯函数，无副作用）。
//
// 权威来源：`docs/03-api/API_CONTRACT.md`（§2.3 / §6 / §11.5 / §11.6）与
// `docs/05-quality/ERROR_CODES.md`（分析族 ANL-70xx / 通用 COM-0009 / COM-0010）。
//
// ★ 只决定「前端该做什么」（继续轮询 / 刷新状态 / 引导补数据 / 重建幂等键 / 停止），
//   不改写服务端文案；展示时仍以 `describeError()` 的服务端 message 为准。
import { errorCodeOf } from '@/utils/error'

/** 前端对分析错误应采取的动作。 */
export type AnalysisErrorAction =
  | 'continue-polling'
  | 'refresh-status'
  | 'data-insufficient'
  | 'recreate-key'
  | 'not-found'
  | 'unknown'

/** 错误处理建议。 */
export interface AnalysisErrorHint {
  action: AnalysisErrorAction
  /** 面向运营人员的一句话提示（服务端 message 之外的操作引导）。 */
  message: string
}

const HINTS: Record<string, AnalysisErrorHint> = {
  // 结果尚未生成（items 409）：不是失败，继续轮询即可
  'ANL-7012': {
    action: 'continue-polling',
    message: '分析结果尚未生成，请等待任务完成（已继续轮询）',
  },
  // 数据质量预检未通过（创建 422）：引导调整范围/窗口
  'ANL-7008': {
    action: 'data-insufficient',
    message: '数据不足，无法完成分析：请扩大日期区间、减少 SKU 范围或降低最少有效天数后重试',
  },
  // 任务已是其它终态，不可取消：刷新拿到最新状态
  'ANL-7011': {
    action: 'refresh-status',
    message: '任务状态已变化，正在刷新最新状态',
  },
  // 幂等键冲突但请求体不一致：同键不能用于不同内容
  'COM-0009': {
    action: 'recreate-key',
    message: '该提交内容与首次提交不一致（幂等键冲突），请重新提交',
  },
  // 幂等键缺失/超长：前端生成的键异常，需重建
  'COM-0010': {
    action: 'recreate-key',
    message: '缺少有效的幂等键，请重新打开表单后再提交',
  },
  // 任务不存在或跨团队（不区分两者）
  'ANL-7005': {
    action: 'not-found',
    message: '分析任务不存在或无权访问',
  },
}

/** 把任意异常映射为前端动作建议。 */
export function classifyAnalysisError(error: unknown): AnalysisErrorHint {
  const code = errorCodeOf(error)
  if (code && HINTS[code]) {
    return HINTS[code]
  }
  return { action: 'unknown', message: '请求失败，请稍后重试' }
}
