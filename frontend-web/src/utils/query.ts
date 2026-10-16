// 查询参数工具（纯函数，无副作用）。
//
// 用途：列表页把「筛选控件状态」转成请求参数时，剔除空值，
// 避免把空串发给后端（如 `productId=` 对 `Long` 型参数是非法输入）。

/**
 * 剔除空值（`''` / `null` / `undefined`）后的浅拷贝。
 * @param params 原始参数（可能含空值）。
 */
export function pruneEmpty(params: Record<string, unknown>): Record<string, unknown> {
  const result: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(params)) {
    if (value !== '' && value !== null && value !== undefined) {
      result[key] = value
    }
  }
  return result
}
