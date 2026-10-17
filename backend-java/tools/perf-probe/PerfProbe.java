import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * NFR-PERF-02 归因探针（可入库、可复现）。
 *
 * <p>用途：分离「业务落库成本」与「审计写入成本」，并对比审计的两种提交粒度，
 * 为 {@code DESIGN_REVIEW §5.1} 的裁定提供可复现证据。纯 JDBC，不依赖本项目任何代码。
 *
 * <p>三段口径（同一进程内顺序执行，各自 TRUNCATE）：
 * <ol>
 *   <li>A 业务落库（含按业务编码回查 id，模拟 upsert 路径，无审计）</li>
 *   <li>B 业务 + 审计<b>逐条独立提交</b>（模拟 {@code REQUIRES_NEW} 每行一次提交）</li>
 *   <li>C 业务 + 审计<b>按批独立提交</b>（同批 N 条审计合并为一个独立事务）</li>
 * </ol>
 *
 * <p>表结构按 {@code V1.0.4__audit.sql} 的 audit_logs（22 列 + 5 索引 + JSON 列 + CHECK）与
 * products 的真实唯一键/索引重建，避免「简化表结构导致 fsync 偏少」的失真。
 *
 * <p>用法：
 * <pre>
 *   java -cp mysql-connector-j-9.x.jar PerfProbe.java \
 *        "jdbc:mysql://127.0.0.1:13399/probe?useSSL=false&amp;allowPublicKeyRetrieval=true" \
 *        root probe 10000 500
 *   # 参数：jdbcUrl user password [rows=10000] [batch=500]
 * </pre>
 *
 * <p>★ 读数口径：B 与 C 的差值 = 「提交粒度」的净影响；A 与 B 的差值 = 审计写入的边际成本。
 * 该探针只度量「提交粒度」与「审计条数」，不覆盖 Spring 事务代理、Hikari 借还、
 * 事件构造/JSON 序列化等框架侧开销——那部分须用真栈观测（见 {@code DESIGN_REVIEW §5.1}）。
 */
public final class PerfProbe {

    private static String url;
    private static String user;
    private static String password;
    private static int rows;
    private static int batch;

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("用法: PerfProbe <jdbcUrl> <user> <password> [rows=10000] [batch=500]");
            System.exit(2);
        }
        url = args[0];
        user = args[1];
        password = args[2];
        rows = args.length > 3 ? Integer.parseInt(args[3]) : 10_000;
        batch = args.length > 4 ? Integer.parseInt(args[4]) : 500;

        System.out.printf("rows=%d batch=%d%n", rows, batch);
        try (Connection c = DriverManager.getConnection(url, user, password)) {
            init(c);
        }
        run("A business only (no audit)", Mode.NONE);
        run("B business + audit, per-row commit", Mode.PER_ROW_COMMIT);
        run("C business + audit, per-batch commit", Mode.PER_BATCH_COMMIT);
        System.out.println("=== done ===");
    }

    private enum Mode { NONE, PER_ROW_COMMIT, PER_BATCH_COMMIT }

    private static void init(Connection c) throws Exception {
        try (Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS products");
            s.execute("DROP TABLE IF EXISTS audit_logs");
            s.execute("CREATE TABLE products ("
                    + " id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                    + " team_id BIGINT UNSIGNED NOT NULL,"
                    + " product_code VARCHAR(64) NOT NULL,"
                    + " name VARCHAR(200) NOT NULL,"
                    + " unit VARCHAR(32) NULL,"
                    + " status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',"
                    + " created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),"
                    + " updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),"
                    + " created_by BIGINT UNSIGNED NULL,"
                    + " updated_by BIGINT UNSIGNED NULL,"
                    + " version INT NOT NULL DEFAULT 0,"
                    + " UNIQUE KEY uk_prod_team_code (team_id, product_code),"
                    + " KEY idx_prod_team_status (team_id, status)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            // 与 V1.0.4__audit.sql 对齐：22 列、5 索引、JSON 列、CHECK
            s.execute("CREATE TABLE audit_logs ("
                    + " id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                    + " team_id BIGINT UNSIGNED NOT NULL,"
                    + " action VARCHAR(64) NOT NULL,"
                    + " biz_type VARCHAR(64) NOT NULL,"
                    + " biz_id BIGINT UNSIGNED NULL,"
                    + " biz_no VARCHAR(64) NULL,"
                    + " operator_id BIGINT UNSIGNED NULL,"
                    + " operator_name VARCHAR(64) NULL,"
                    + " operator_role VARCHAR(32) NULL,"
                    + " http_method VARCHAR(8) NULL,"
                    + " request_uri VARCHAR(255) NULL,"
                    + " request_id VARCHAR(64) NULL,"
                    + " ip VARCHAR(64) NULL,"
                    + " user_agent VARCHAR(255) NULL,"
                    + " result VARCHAR(16) NOT NULL DEFAULT 'SUCCESS',"
                    + " error_code VARCHAR(32) NULL,"
                    + " change_summary VARCHAR(1000) NULL,"
                    + " before_json JSON NULL,"
                    + " after_json JSON NULL,"
                    + " occurred_at DATETIME(3) NOT NULL,"
                    + " created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),"
                    + " created_by BIGINT UNSIGNED NULL,"
                    + " KEY idx_al_team_biz_time (team_id, biz_type, biz_id, occurred_at),"
                    + " KEY idx_al_team_operator_time (team_id, operator_id, occurred_at),"
                    + " KEY idx_al_team_action_time (team_id, action, occurred_at),"
                    + " KEY idx_al_team_request (team_id, request_id),"
                    + " KEY idx_al_team_result_time (team_id, result, occurred_at),"
                    + " CONSTRAINT ck_al_result CHECK (result IN ('SUCCESS','FAILURE'))"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }
    }

    private static void run(String label, Mode mode) throws Exception {
        try (Connection biz = DriverManager.getConnection(url, user, password);
             Connection side = DriverManager.getConnection(url, user, password)) {
            biz.setAutoCommit(true);
            try (Statement s = biz.createStatement()) {
                s.execute("TRUNCATE TABLE products");
                s.execute("TRUNCATE TABLE audit_logs");
            }
            long fsync0 = fsyncs(biz);
            long t0 = System.nanoTime();

            for (int from = 0; from < rows; from += batch) {
                int to = Math.min(from + batch, rows);
                biz.setAutoCommit(false);
                try (PreparedStatement ins = biz.prepareStatement(
                        "INSERT INTO products (team_id, product_code, name, unit, status, created_by, updated_by) "
                                + "VALUES (7, ?, ?, '件', 'ACTIVE', 1001, 1001)");
                     PreparedStatement sel = biz.prepareStatement(
                             "SELECT id FROM products WHERE team_id = 7 AND product_code = ?")) {
                    for (int i = from; i < to; i++) {
                        String code = String.format("P-PERF-%05d", i + 1);
                        ins.setString(1, code);
                        ins.setString(2, "压测商品" + (i + 1));
                        ins.executeUpdate();
                        sel.setString(1, code);
                        try (ResultSet rs = sel.executeQuery()) {
                            rs.next();
                        }
                        if (mode == Mode.PER_ROW_COMMIT) {
                            side.setAutoCommit(false);
                            writeAudit(side, i, code);
                            side.commit();
                        }
                    }
                }
                biz.commit();

                if (mode == Mode.PER_BATCH_COMMIT) {
                    side.setAutoCommit(false);
                    for (int i = from; i < to; i++) {
                        writeAudit(side, i, String.format("P-PERF-%05d", i + 1));
                    }
                    side.commit();
                }
            }

            long ms = (System.nanoTime() - t0) / 1_000_000L;
            long fsyncs = fsyncs(biz) - fsync0;
            long auditRows = count(biz, "SELECT COUNT(*) FROM audit_logs");
            System.out.printf("%-34s %7d ms | %6.2f ms/row | fsyncs=%7d | audit_rows=%d%n",
                    label, ms, ms / (double) rows, fsyncs, auditRows);
        }
    }

    /** 单条审计写入；提交由调用方控制（逐条提交 = 每行 commit，按批提交 = 批末一次 commit）。 */
    private static void writeAudit(Connection side, int i, String code) throws Exception {
        try (PreparedStatement a = side.prepareStatement(
                "INSERT INTO audit_logs (team_id, action, biz_type, biz_id, biz_no, "
                        + "operator_id, operator_name, operator_role, http_method, request_uri, "
                        + "result, change_summary, after_json, occurred_at, created_by) "
                        + "VALUES (7,'MASTER_DATA_STATUS_CHANGE','PRODUCT',?,?,'1001','operator01',"
                        + "'OPERATOR','POST','/api/v1/import/batches/1/confirm','SUCCESS',"
                        + "'导入落库：PRODUCT 新建',?, '2026-10-17 03:00:00.000',1001)")) {
            a.setLong(1, i + 1);
            a.setString(2, code);
            a.setString(3, "{\"status\":{\"before\":null,\"after\":\"ACTIVE\"}}");
            a.executeUpdate();
        }
    }

    private static long fsyncs(Connection c) throws Exception {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SHOW GLOBAL STATUS LIKE 'Innodb_data_fsyncs'")) {
            return rs.next() ? Long.parseLong(rs.getString(2)) : -1L;
        }
    }

    private static long count(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : -1L;
        }
    }
}
