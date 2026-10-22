// 精确十进制运算（**只用字符串 + BigInt**，绝不经过 `Number`）。
//
// 存在理由：口径铁律 ④ 要求数量（scale=3）/ 单价（scale=4）/ 金额（scale=2）**原样字符串展示**，
// 不得 `Number()` 换算。但界面偶尔需要「派生一个只读数字」用于辅助判断，典型场景是
// 到货登记页的「待收数量 = 订购量 − 已收量」——这是让运营一眼看出还能收多少，不参与任何提交。
//
// ★ 本文件**只做精确整数化加减**：把 `"120.000"` 按 scale 右移成 BigInt 分度值再相减，
//   结果再按 scale 拼回字符串。`0.1 + 0.2` 一类浮点误差在这里不可能出现。
// ★ 非法输入（非数字 / 小数位超过 scale）**抛错**，不静默截断——静默截断会把脏数据藏起来。

/** 把十进制字符串解析为「按 scale 放大后的整数」（BigInt，避免 2^53 溢出）。 */
function toMinorUnits(text: string, scale: number): bigint {
  const trimmed = (text ?? '').trim()
  if (!/^-?\d+(\.\d+)?$/.test(trimmed)) {
    throw new Error(`非法十进制数：${text}`)
  }
  const negative = trimmed.startsWith('-')
  const unsigned = negative ? trimmed.slice(1) : trimmed
  const [intPart, rawFraction = ''] = unsigned.split('.')
  if (rawFraction.length > scale) {
    throw new Error(`小数位超过 ${scale} 位：${text}`)
  }
  const magnitude = BigInt(`${intPart}${rawFraction.padEnd(scale, '0')}`)
  return negative ? -magnitude : magnitude
}

/** 把「按 scale 放大后的整数」还原为定点数字符串（补齐尾零，保持 scale 位小数）。 */
function fromMinorUnits(value: bigint, scale: number): string {
  const negative = value < 0n
  const digits = (negative ? -value : value).toString().padStart(scale + 1, '0')
  const cut = digits.length - scale
  const intPart = digits.slice(0, cut)
  const fraction = scale === 0 ? '' : `.${digits.slice(cut)}`
  return `${negative ? '-' : ''}${intPart}${fraction}`
}

/**
 * 精确减法：`minuend - subtrahend`，结果固定 `scale` 位小数。
 *
 * @example subtractDecimal('120.000', '40.000') // '80.000'
 * @example subtractDecimal('0.000', '10.000')   // '-10.000'
 * @param scale 小数位数（数量用 3，单价用 4，金额用 2）
 */
export function subtractDecimal(minuend: string, subtrahend: string, scale = 3): string {
  return fromMinorUnits(
    toMinorUnits(minuend, scale) - toMinorUnits(subtrahend, scale),
    scale,
  )
}

/**
 * 判断定点数字符串是否 `> 0`（同样不经过 `Number`）。
 *
 * 用途：判断「待收数量还有没有剩余」这类布尔判定。
 */
export function isPositiveDecimal(text: string, scale = 3): boolean {
  return toMinorUnits(text, scale) > 0n
}
