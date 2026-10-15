// 格式化工具（纯函数，无副作用，可被所有层使用）。
// 注意：团队时区应由服务端下发，前端按服务端时区格式化；此处先用本地时区占位。

/** 格式化日期时间。 */
export function formatDateTime(value: string | number | Date, fallback = '-'): string {
  if (!value) return fallback
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return fallback
  const pad = (n: number) => String(n).padStart(2, '0')
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ` +
    `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
  )
}

/** 格式化数量（默认保留 2 位小数，去除多余尾零）。 */
export function formatQuantity(value: number, digits = 2): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '-'
  return Number(value.toFixed(digits)).toString()
}

/** 格式化金额（默认保留两位小数，前缀 ¥）。 */
export function formatAmount(value: number, currency = '¥', digits = 2): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '-'
  return `${currency}${value.toFixed(digits)}`
}
