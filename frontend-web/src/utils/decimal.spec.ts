// 精确十进制运算单测（口径铁律 ④：数量/单价/金额不经过 `Number`）。
//
// 探针目标：把「浮点误差」钉死在门外 —— 每个用例都是 `Number` 版本会算错或会引入
// 尾数误差的组合（如 0.1+0.2 类、大数超 2^53 类、负结果类）。
import { describe, expect, it } from 'vitest'
import { isPositiveDecimal, subtractDecimal } from './decimal'

describe('subtractDecimal', () => {
  it('数量 scale=3：120.000 − 40.000 = 80.000（补足尾零，保持三位小数）', () => {
    expect(subtractDecimal('120.000', '40.000')).toBe('80.000')
  })

  it('数量 scale=3：100.500 − 0.250 = 100.250（小数位精确，无浮点尾数）', () => {
    expect(subtractDecimal('100.500', '0.250')).toBe('100.250')
  })

  it('结果为负时保留符号：0.000 − 10.000 = -10.000（到货超额判定依赖此语义）', () => {
    expect(subtractDecimal('0.000', '10.000')).toBe('-10.000')
  })

  it('相减为零：1.000 − 1.000 = 0.000（不出现 "-0.000"）', () => {
    expect(subtractDecimal('1.000', '1.000')).toBe('0.000')
  })

  it('单价 scale=4：10.0000 − 0.0001 = 9.9999（四位小数逐位精确）', () => {
    expect(subtractDecimal('10.0000', '0.0001', 4)).toBe('9.9999')
  })

  it('超出 2^53 的整数部分仍精确（BigInt 分度值，不经 Number）', () => {
    // Number('9007199254740993') 已失真为 ...992，减法会错 1
    expect(subtractDecimal('9007199254740993.000', '0.001')).toBe('9007199254740992.999')
  })

  it('非法输入抛错，不静默当 0（脏数据必须暴露）', () => {
    expect(() => subtractDecimal('abc', '1.000')).toThrow('非法十进制数')
    expect(() => subtractDecimal('', '1.000')).toThrow('非法十进制数')
    // 数量列 scale=3，多于 3 位小数属于契约外的数据
    expect(() => subtractDecimal('1.0000', '1.000')).toThrow('小数位超过 3 位')
  })
})

describe('isPositiveDecimal', () => {
  it('正数 true / 零 false / 负数 false', () => {
    expect(isPositiveDecimal('0.001')).toBe(true)
    expect(isPositiveDecimal('0.000')).toBe(false)
    expect(isPositiveDecimal('-0.001')).toBe(false)
  })
})
