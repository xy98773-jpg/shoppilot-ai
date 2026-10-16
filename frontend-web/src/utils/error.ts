// 前端可读错误信息提取（纯函数，无副作用，不依赖任何层）。
//
// 服务端统一响应包络（`API_CONTRACT §2.3`）经 `api/http.ts` 转换为携带
// `code` / `message` / `details` / `requestId` 的结构化错误，本模块负责把它转成
// 「可直接展示给运营人员」的一行文案（含 `COM-0001` 的字段级细节）。

/** 结构化错误的最小形状（与 `api/http.ts` 的 `ApiError` 结构一致，此处仅按需声明，避免 utils 依赖 api 层）。 */
interface CodedError {
  code?: string
  message?: string
  details?: { field: string; message: string }[]
  requestId?: string
  httpStatus?: number
}

/** 取出错误码（非结构化错误返回 undefined）。 */
export function errorCodeOf(error: unknown): string | undefined {
  return (error as CodedError | undefined)?.code
}

/**
 * 把任意异常转成面向用户的中文提示。
 * - 有 `details[]`（字段级校验错误，如 `COM-0001`）→ 追加 `字段: 说明` 明细；
 * - 其余一律 `[错误码] 服务端 message`（无错误码时退化为原始 message）。
 */
export function describeError(error: unknown): string {
  const err = error as CodedError | undefined
  if (!err || (!err.code && !err.message)) {
    return '请求失败，请稍后重试'
  }
  const prefix = err.code ? `[${err.code}] ` : ''
  const base = err.message || '请求失败'
  const details = err.details
  if (details && details.length > 0) {
    const text = details.map((d) => `${d.field}: ${d.message}`).join('；')
    return `${prefix}${base}（${text}）`
  }
  return `${prefix}${base}`
}
