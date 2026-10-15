package com.shoppilot.common.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * MySQL 版本守卫（启动期断言，{@code DATABASE_CONSTRAINTS.md §8.3 C-11}）。
 *
 * <p>背景：{@code CHECK} 约束自 <b>MySQL 8.0.16</b> 起才生效（{@code DESIGN_BASIS §4.1}）；
 * 低于该版本时 {@code CHECK} 会被 <b>静默忽略</b>，导致枚举/非负校验整体失效且无任何报错。
 * 因此启动时必须显式断言 {@code SELECT VERSION() >= 8.0.16}，不满足即阻断启动。
 *
 * <p>断言口径等价于「版本 ≥ 8.0.16」：{@code major > 8} 视为满足；
 * {@code major == 8} 时要求 {@code minor > 0}，或 {@code minor == 0} 且 {@code patch >= 16}。
 *
 * <p><b>边界</b>：本类属 common（基础设施），只依赖 {@link DataSource}（java.sql + 框架），
 * 不含任何业务名词与业务规则。
 */
@Component
public class MySQLVersionGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MySQLVersionGuard.class);

    /** 要求的最低版本：8.0.16（CHECK 约束生效的最低版本）。 */
    static final int MIN_MAJOR = 8;
    static final int MIN_MINOR = 0;
    static final int MIN_PATCH = 16;

    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");

    private final DataSource dataSource;

    public MySQLVersionGuard(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws SQLException {
        String rawVersion = queryVersion();
        log.info("[C-11] MySQL 版本守卫：检测到 {}，要求 >= {}.{}.{}",
                rawVersion, MIN_MAJOR, MIN_MINOR, MIN_PATCH);
        assertSupported(rawVersion);
        log.info("[C-11] MySQL 版本守卫：通过（CHECK 约束可用）");
    }

    /** 读取 {@code SELECT VERSION()} 原始结果。 */
    String queryVersion() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT VERSION()")) {
            if (!rs.next()) {
                throw new IllegalStateException("[C-11] MySQL 版本守卫：SELECT VERSION() 未返回任何结果");
            }
            return rs.getString(1);
        }
    }

    /**
     * 断言版本满足 ≥ 8.0.16，否则抛出 {@link IllegalStateException} 阻断启动。
     *
     * <p>可见性为包级（非 private）以便单元测试直接断言，无需启动 Spring 上下文。
     */
    static void assertSupported(String rawVersion) {
        Matcher matcher = VERSION_PATTERN.matcher(rawVersion == null ? "" : rawVersion);
        if (!matcher.find()) {
            throw new IllegalStateException(
                    "[C-11] MySQL 版本守卫：无法从 \"" + rawVersion + "\" 解析出版本号（期望形如 8.0.36）");
        }
        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));
        int patch = Integer.parseInt(matcher.group(3));

        boolean supported = major > MIN_MAJOR
                || (major == MIN_MAJOR && (minor > MIN_MINOR || (minor == MIN_MINOR && patch >= MIN_PATCH)));

        if (!supported) {
            throw new IllegalStateException(String.format(
                    "[C-11] MySQL 版本守卫：当前版本 %d.%d.%d 低于要求的 %d.%d.%d，"
                            + "CHECK 约束会被静默忽略，拒绝启动（DATABASE_CONSTRAINTS.md §8.3 C-11）",
                    major, minor, patch, MIN_MAJOR, MIN_MINOR, MIN_PATCH));
        }
    }
}
