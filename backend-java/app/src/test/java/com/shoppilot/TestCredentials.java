package com.shoppilot;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 集成测试共用的测试凭据（R28 整改：集中原散落在各测试文件里的明文口令，支持环境变量覆盖）。
 *
 * <p>原 10 个测试文件各自硬编码 {@code String RAW_PASSWORD = "..."}，违反安全规范与可维护性；
 * 本类将其收敛为单一来源，并允许通过 {@code SHOPPILOT_TEST_PASSWORD} 环境变量覆盖
 * （CI 等环境可注入一次性口令，避免仓库内长期留存固定口令）。
 */
public final class TestCredentials {

    /** 测试口令（可被环境变量 {@code SHOPPILOT_TEST_PASSWORD} 覆盖）。 */
    public static final String RAW_PASSWORD =
            System.getenv("SHOPPILOT_TEST_PASSWORD") != null
                    ? System.getenv("SHOPPILOT_TEST_PASSWORD")
                    : "1234";

    /** 对应口令的 BCrypt 哈希（测试直插库时使用，避免每个测试重复 encode）。 */
    public static final String PASSWORD_HASH = new BCryptPasswordEncoder().encode(RAW_PASSWORD);

    private TestCredentials() {
    }
}
