// 分析错误分类单测（M4-B21b）。
//
// 覆盖任务书要求的前端处理口径：
//   ANL-7012 → 继续轮询；ANL-7008 → 数据不足引导；ANL-7011 → 刷新状态；
//   COM-0009 / COM-0010 → 重建幂等键；ANL-7005 → 任务不存在。
import { describe, expect, it } from 'vitest'
import { classifyAnalysisError } from './analysisError'

describe('classifyAnalysisError', () => {
  it('ANL-7012 结果未就绪：继续轮询', () => {
    const hint = classifyAnalysisError({ code: 'ANL-7012', message: '分析结果尚未生成' })
    expect(hint.action).toBe('continue-polling')
    expect(hint.message).toContain('继续轮询')
  })

  it('ANL-7008 数据不足：引导补数据', () => {
    const hint = classifyAnalysisError({ code: 'ANL-7008', message: '所选数据不满足分析条件' })
    expect(hint.action).toBe('data-insufficient')
    expect(hint.message).toContain('数据不足')
  })

  it('ANL-7011 其它终态不可取消：刷新状态', () => {
    const hint = classifyAnalysisError({ code: 'ANL-7011', message: '当前状态不可取消' })
    expect(hint.action).toBe('refresh-status')
  })

  it('COM-0009 幂等键冲突：重建键', () => {
    const hint = classifyAnalysisError({ code: 'COM-0009', message: '幂等键冲突但请求体不一致' })
    expect(hint.action).toBe('recreate-key')
  })

  it('COM-0010 缺少幂等键：重建键', () => {
    const hint = classifyAnalysisError({ code: 'COM-0010', message: '缺少必要的请求信息' })
    expect(hint.action).toBe('recreate-key')
  })

  it('ANL-7005 任务不存在：停止轮询', () => {
    const hint = classifyAnalysisError({ code: 'ANL-7005', message: '分析任务不存在' })
    expect(hint.action).toBe('not-found')
  })

  it('未知错误：unknown', () => {
    expect(classifyAnalysisError(new Error('boom')).action).toBe('unknown')
    expect(classifyAnalysisError(undefined).action).toBe('unknown')
  })
})
