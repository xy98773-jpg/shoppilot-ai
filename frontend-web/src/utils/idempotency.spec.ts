// 幂等键容器单测（口径铁律 ③：一次用户意图一个键，重试复用）。
import { describe, expect, it } from 'vitest'
import { createIdempotencyIntent, newIdempotencyKey } from './idempotency'

describe('idempotency', () => {
  it('newIdempotencyKey 生成非空字符串，且多次调用不同', () => {
    const a = newIdempotencyKey()
    const b = newIdempotencyKey()
    expect(a.length).toBeGreaterThan(0)
    expect(a).not.toBe(b)
  })

  it('一次意图内 key() 稳定（失败重试必须复用同一键）', () => {
    const intent = createIdempotencyIntent()
    const first = intent.key()
    expect(intent.key()).toBe(first)
    expect(intent.key()).toBe(first)
    expect(intent.has()).toBe(true)
  })

  it('reset() 后换新键（用户完成/放弃后是新的一次意图）', () => {
    const intent = createIdempotencyIntent()
    const first = intent.key()
    intent.reset()
    expect(intent.has()).toBe(false)
    const second = intent.key()
    expect(second).not.toBe(first)
  })

  it('两个意图容器互不影响', () => {
    const a = createIdempotencyIntent()
    const b = createIdempotencyIntent()
    expect(a.key()).not.toBe(b.key())
    a.reset()
    const fresh = a.key()
    expect(fresh).not.toBe(b.key())
  })
})
