package com.shoppilot.common.db;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MySQL 版本守卫 —— 端到端集成测试（需 Docker，由 Testcontainers 提供）。
 *
 * <p>价值：把 M1-T05 那次「手工起 mysql:8.0.12 → 应用 exit=1、18081 未监听」的取证，
 * 固化为每次 CI 都跑的可重复断言（DEVELOPMENT_PLAN:205）。守卫是 {@code ApplicationRunner}，
 * 本测试不启动 Spring 上下文，而是手搓一个指向真实 8.0.12 实例的 {@link DataSource}，
 * 直接驱动 {@link MySQLVersionGuard#run(org.springframework.boot.ApplicationArguments)}，
 * 断言其抛出 {@link IllegalStateException} 阻断启动。
 *
 * <p>GitHub Actions 的 ubuntu-latest 自带 Docker 守护进程，Testcontainers 开箱即用。
 */
@Testcontainers
class MySQLVersionGuardIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL_8_0_12 = new MySQLContainer<>("mysql:8.0.12")
            // 与生产一致：时区统一 UTC（DATABASE_DESIGN：DATETIME(3) 存 UTC）
            .withCommand("--default-time-zone=+00:00");

    @Test
    void lowVersionBlocksStartup() {
        DataSource dataSource = new DriverManagerDataSource(
                MYSQL_8_0_12.getJdbcUrl(),
                MYSQL_8_0_12.getUsername(),
                MYSQL_8_0_12.getPassword());

        MySQLVersionGuard guard = new MySQLVersionGuard(dataSource);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> guard.run(null));
        assertTrue(ex.getMessage().contains("8.0.12"), ex.getMessage());
        assertTrue(ex.getMessage().contains("8.0.16"), ex.getMessage());
    }
}
