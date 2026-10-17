// 幂等键管理（纯逻辑，无副作用；`API_CONTRACT §6.3` + 口径铁律 ③）。
//
// 规则：**一次用户意图一个键**——打开表单/发起动作时生成，失败重试**复用同一键**，
// 只有「用户完成/放弃这一次操作」后才重置。这样网络超时后的重试命中服务端既有记录，
// 不会产生第二条业务记录。
//
// ★ 前端生成键只是「请求去重线索」；真正的幂等与唯一性由服务端保证。

/** 生成一个 UUID v4（无加密随机源时退化为时间戳 + 随机数，仅用于去重）。 */
export function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`
}

/** 一次用户意图的幂等键容器。 */
export interface IdempotencyIntent {
  /** 取本次意图的键（首次调用生成，之后复用）。 */
  key: () => string
  /** 结束本次意图（成功 / 用户放弃）：下次调用将生成新键。 */
  reset: () => void
  /** 是否已生成过键（测试与调试用）。 */
  has: () => boolean
}

/**
 * 创建一个「一次意图一键」的容器。
 *
 * @example
 * const intent = createIdempotencyIntent()
 * await adjustInventory(payload, intent.key())   // 失败重试仍用同一键
 * intent.reset()                                  // 成功后重置
 */
export function createIdempotencyIntent(): IdempotencyIntent {
  let current: string | null = null
  return {
    key: () => {
      if (current === null) {
        current = newIdempotencyKey()
      }
      return current
    },
    reset: () => {
      current = null
    },
    has: () => current !== null,
  }
}
