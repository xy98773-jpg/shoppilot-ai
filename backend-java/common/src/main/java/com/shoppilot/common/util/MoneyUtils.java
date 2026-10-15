package com.shoppilot.common.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 金额精度工具（基础设施）。
 *
 * <p>金额列 DECIMAL(18,2) → scale 2，统一 HALF_UP 舍入。<b>不含任何业务计算规则</b>。
 */
public final class MoneyUtils {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private MoneyUtils() {
    }

    public static BigDecimal of(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.setScale(SCALE, ROUNDING);
    }

    public static BigDecimal add(BigDecimal a, BigDecimal b) {
        if (a == null) {
            a = BigDecimal.ZERO;
        }
        if (b == null) {
            b = BigDecimal.ZERO;
        }
        return a.add(b).setScale(SCALE, ROUNDING);
    }

    public static BigDecimal subtract(BigDecimal a, BigDecimal b) {
        if (a == null) {
            a = BigDecimal.ZERO;
        }
        if (b == null) {
            b = BigDecimal.ZERO;
        }
        return a.subtract(b).setScale(SCALE, ROUNDING);
    }

    public static BigDecimal multiply(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return null;
        }
        return a.multiply(b).setScale(SCALE, ROUNDING);
    }

    public static int compare(BigDecimal a, BigDecimal b) {
        BigDecimal x = a == null ? BigDecimal.ZERO : a;
        BigDecimal y = b == null ? BigDecimal.ZERO : b;
        return x.compareTo(y);
    }

    public static boolean isNegative(BigDecimal value) {
        return value != null && value.signum() < 0;
    }

    public static boolean isNonNegative(BigDecimal value) {
        return value != null && value.signum() >= 0;
    }
}
