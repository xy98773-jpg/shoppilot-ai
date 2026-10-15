package com.shoppilot.common.db;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MySQL 版本守卫 —— 解析口径纯单测（无需 Docker）。
 *
 * <p>覆盖 {@code assertSupported(String)} 的等价语义「版本 ≥ 8.0.16」：
 * major>8 通过；major==8 时 minor>0 或 (minor==0 && patch>=16) 通过；其余抛 {@link IllegalStateException}。
 *
 * <p>真实「低于阈值 → 启动被阻断」的端到端证据由 {@link MySQLVersionGuardIntegrationTest}（Testcontainers 拉
 * mysql:8.0.12）提供；本类只验证解析/判定逻辑本身，跑得快、零外部依赖。
 */
class MySQLVersionGuardParserTest {

    @Test
    void supportedVersionsDoNotThrow() {
        assertDoesNotThrow(() -> MySQLVersionGuard.assertSupported("8.0.16"));
        assertDoesNotThrow(() -> MySQLVersionGuard.assertSupported("8.0.36"));
        // 等价语义：major>8 一律通过，不能误判成不满足
        assertDoesNotThrow(() -> MySQLVersionGuard.assertSupported("9.0.0"));
    }

    @Test
    void unsupportedVersionsThrow() {
        IllegalStateException belowMin = assertThrows(IllegalStateException.class,
                () -> MySQLVersionGuard.assertSupported("8.0.12"));
        assertTrue(belowMin.getMessage().contains("8.0.12"), belowMin.getMessage());
        assertTrue(belowMin.getMessage().contains("8.0.16"), belowMin.getMessage());

        // 8.0.15 仍低于 8.0.16
        assertThrows(IllegalStateException.class, () -> MySQLVersionGuard.assertSupported("8.0.15"));
        // 5.x 整支不满足
        assertThrows(IllegalStateException.class, () -> MySQLVersionGuard.assertSupported("5.7.40"));
    }

    @Test
    void unparseableVersionThrows() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> MySQLVersionGuard.assertSupported("not-a-version"));
        assertTrue(ex.getMessage().contains("无法"), ex.getMessage());
    }
}
