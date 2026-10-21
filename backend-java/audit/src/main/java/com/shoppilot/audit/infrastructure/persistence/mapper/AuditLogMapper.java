package com.shoppilot.audit.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.shoppilot.audit.infrastructure.persistence.entity.AuditLogEntity;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code audit_logs} Mapper —— <b>仅追加</b>（{@code DATABASE_DESIGN.md §6-J.4} 硬要求）。
 *
 * <h2>★ 为什么不继承 {@code BaseMapper}</h2>
 * {@code BaseMapper} 自带 {@code updateById} / {@code deleteById} / {@code delete*} 等写方法，
 * 与本表"仅 INSERT"的强制约束冲突。故本接口<b>不继承任何父接口</b>，只声明
 * {@link #insert} 与只读查询；单元测试断言接口方法集合中除 {@code insert} 外无任何写方法。
 *
 * <h2>★★ 租户线口径（本批头号陷阱）</h2>
 * {@code audit_logs} <b>有</b> {@code team_id} 列 → {@code TenantLineInnerInterceptor} 默认会改写它的
 * SQL（SELECT/UPDATE/DELETE 追加 {@code AND team_id=?}，INSERT 自动补列）。而
 * {@code ShopPilotTenantLineHandler#getTenantId()} 用 {@code TeamContext.require()}，
 * <b>上下文缺失即抛 {@code AUTH-1001}</b>（M2-T06 既定防线，不改）——
 * 登录失败 / 系统任务等无团队上下文场景会<b>直接写不进去</b>。
 *
 * <p>修法：本接口两个方法都标 {@code @InterceptorIgnore(tenantLine="true")}：
 * <ul>
 *   <li>{@link #insert}：{@code team_id} 由调用方（{@code AuditWriteService}）从事件显式取值
 *       （有上下文取其值；无上下文字段传 {@code 0} 系统域），<b>不依赖</b>租户线补齐；</li>
 *   <li>{@link #selectRecentByBiz}：已绕过租户线，故<b>必须显式</b>带 {@code team_id} 条件
 *       （由调用方传入服务端口径的团队 ID，绝不接受客户端传入）。</li>
 * </ul>
 *
 * <p>❌ 不把 {@code audit_logs} 登记进 {@code TenantTables.WITHOUT_TEAM_ID}：
 * 该黑名单只给<b>没有</b> {@code team_id} 列的表；本表有该列，登记进去会让租户线彻底失效
 * → 审计跨团队泄露。
 */
@Mapper
public interface AuditLogMapper {

    /**
     * 追加一条审计记录（唯一写入口）。
     *
     * <p>{@code team_id} 列<b>不出现在 SQL 的动态补列逻辑里</b>（已绕过租户线），
     * 由实体显式携带；{@code created_at} 由 DB 默认值填充，不在列清单中。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Insert("INSERT INTO audit_logs (team_id, action, biz_type, biz_id, biz_no, operator_id, "
            + "operator_name, operator_role, http_method, request_uri, request_id, ip, user_agent, "
            + "result, error_code, change_summary, before_json, after_json, occurred_at, created_by) "
            + "VALUES (#{teamId}, #{action}, #{bizType}, #{bizId}, #{bizNo}, #{operatorId}, "
            + "#{operatorName}, #{operatorRole}, #{httpMethod}, #{requestUri}, #{requestId}, #{ip}, "
            + "#{userAgent}, #{result}, #{errorCode}, #{changeSummary}, #{beforeJson}, #{afterJson}, "
            + "#{occurredAt}, #{createdBy})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(AuditLogEntity entity);

    /**
     * <b>多行</b> {@code INSERT}（{@code ADR-004 D1}「多行 {@code INSERT} 或 {@code ExecutorType.BATCH}」
     * 的第一种，{@code B4-PERF} 采用）。
     *
     * <h2>为什么不是循环 {@link #insert}</h2>
     * 循环 500 次 = 500 次 SQL 解析 + 500 次往返（实测单行审计约 {@code 3.5 ms}，其中 MyBatis +
     * 租户线解析占一半以上）。多行 {@code INSERT} 把同一批压成<b>一条</b>语句，
     * 审计<b>条数完全不变</b>（{@code n} 个 {@code VALUES} 元组）、提交次数也不变（仍由
     * {@code REQUIRES_NEW} 事务控制）——{@code ADR-004 D3} 的「条数不减少」红线不受影响。
     *
     * <p>用完即弃的批量写入<b>不需要</b>回填自增主键（审计 id 不参与后续逻辑），故不带
     * {@code useGeneratedKeys}（多行回填本身也不可靠）。
     *
     * <h2>为什么不加租户线忽略注解（与类内其它方法不同）</h2>
     * {@code team_id} 已在本语句里<b>显式列明</b>（列清单第一项 + 每个 {@code VALUES} 元组都有
     * {@code #{e.teamId}}）。{@code TenantLineInnerInterceptor#processInsert} 会先问
     * {@code TenantLineHandler#ignoreInsert(columns, tenantIdColumn)}：列里已有租户列即<b>短路返回</b>，
     * 不会重复追加、也不会去取 {@code TeamContext}。故无需绕过租户线——
     * 这也让本类「新增租户线忽略注解行 = 0」的约束得以维持。
     *
     * @param entities 非空列表（调用方保证非空；元素非 {@code null}）
     */
    @Insert("<script>"
            + "INSERT INTO audit_logs (team_id, action, biz_type, biz_id, biz_no, operator_id, "
            + "operator_name, operator_role, http_method, request_uri, request_id, ip, user_agent, "
            + "result, error_code, change_summary, before_json, after_json, occurred_at, created_by) VALUES "
            + "<foreach collection='list' item='e' separator=','>"
            + "(#{e.teamId}, #{e.action}, #{e.bizType}, #{e.bizId}, #{e.bizNo}, #{e.operatorId}, "
            + "#{e.operatorName}, #{e.operatorRole}, #{e.httpMethod}, #{e.requestUri}, #{e.requestId}, "
            + "#{e.ip}, #{e.userAgent}, #{e.result}, #{e.errorCode}, #{e.changeSummary}, "
            + "#{e.beforeJson}, #{e.afterJson}, #{e.occurredAt}, #{e.createdBy})"
            + "</foreach>"
            + "</script>")
    int insertBatch(@Param("list") List<AuditLogEntity> entities);

    /**
     * 按业务对象读最近的审计记录（只读；走 {@code idx_al_team_biz_time}）。
     *
     * <p><b>显式带 {@code team_id}</b>：本方法已绕过租户线，团队隔离由参数的
     * {@code teamId}（服务端口径）保证。供 M6-T09 审计查询与联调取证复用；
     * 本批（M2-T11）不暴露 HTTP 查询端点。
     *
     * @param teamId  团队 ID（服务端来源，0 = 系统域）
     * @param bizType 业务对象类型
     * @param bizId   业务对象 ID
     * @param limit   返回条数上限（防御性限制，防全表扫描）
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT id, team_id, action, biz_type, biz_id, biz_no, operator_id, operator_name, "
            + "operator_role, http_method, request_uri, request_id, ip, user_agent, result, error_code, "
            + "change_summary, before_json, after_json, occurred_at, created_at, created_by "
            + "FROM audit_logs "
            + "WHERE team_id = #{teamId} AND biz_type = #{bizType} AND biz_id = #{bizId} "
            + "ORDER BY occurred_at DESC, id DESC LIMIT #{limit}")
    List<AuditLogEntity> selectRecentByBiz(@Param("teamId") long teamId,
                                           @Param("bizType") String bizType,
                                           @Param("bizId") long bizId,
                                           @Param("limit") int limit);

    /**
     * 审计日志分页查询（{@code M6-T09} / {@code F-AUD-02}，{@code API_CONTRACT §10.10}）。
     *
     * <h2>★ 团队隔离（本方法<strong>不</strong>加 {@code @InterceptorIgnore}）</h2>
     * {@code audit_logs} 含 {@code team_id} 列，且未登记进 {@code TenantTables.WITHOUT_TEAM_ID}
     * ⇒ {@code TenantLineInnerInterceptor} 会为本 SELECT <b>自动追加</b> {@code AND team_id = ?}
     * （值来自 {@code TeamContext}）。这正是查询端点需要的隔离，故<b>刻意不加</b>忽略注解——
     * 一旦加上就必须把本方法登记进 {@code M2_TenantIsolationDbIntegrationTest.TENANT_LINE_EXCEPTIONS}，
     * 那等于悄悄关掉团队隔离。{@link #insert} / {@link #selectRecentByBiz} 的忽略是历史写法
     * （无团队上下文也要能写、且已显式带 {@code team_id}），本批<b>不改动</b>。
     *
     * <h2>SQL 约束</h2>
     * <ul>
     *   <li><b>仅 SELECT</b>：{@code audit_logs} 仅追加（{@code DATABASE_DESIGN §6-J.4}），
     *       本接口不继承 {@code BaseMapper}，不新增任何 UPDATE/DELETE；</li>
     *   <li><b>排序方向不拼接</b>：{@code 白名单只有 occurred_at,asc|desc}，由
     *       {@code <choose>} 在两个固定片段间选择，参数永远走 {@code #{}} 占位符；</li>
     *   <li><b>分页</b>：{@code LIMIT #{offset}, #{limit}}；总数由
     *       {@link #selectAuditLogCount} 单独统计（INFO 级量级下比窗口函数更易命中索引）；</li>
     *   <li><b>时间列</b>：筛选/排序一律用 {@code occurred_at}（业务发生时间），不用 {@code created_at}。</li>
     * </ul>
     *
     * @param bizType       业务对象类型（{@code null} = 不过滤）
     * @param bizId         业务对象 ID（{@code null} = 不过滤；非空时调用方保证 {@code bizType} 也非空）
     * @param action        动作（{@code null} = 不过滤）
     * @param result        {@code SUCCESS}/{@code FAILURE}（{@code null} = 不过滤）
     * @param operatorId    操作人（{@code null} = 不过滤）
     * @param dateFrom      起始时间（含，UTC；{@code null} = 不限）
     * @param dateToBefore  结束时间（<b>不含</b>，UTC；调用方已把 {@code date_to} 加 1 天）
     * @param ascending     {@code true=occurred_at ASC}，{@code false=DESC}（缺省）
     * @param offset        偏移量（{@code (page-1)*pageSize}）
     * @param limit         每页条数（已夹取到 1..200）
     */
    @Select("<script>"
            + "SELECT id, action, biz_type, biz_id, biz_no, operator_id, operator_name, operator_role, "
            + "result, error_code, change_summary, request_id, ip, occurred_at, before_json, after_json "
            + "FROM audit_logs "
            + "<where>"
            + "<if test='bizType != null'>AND biz_type = #{bizType} </if>"
            + "<if test='bizId != null'>AND biz_id = #{bizId} </if>"
            + "<if test='action != null'>AND action = #{action} </if>"
            + "<if test='result != null'>AND result = #{result} </if>"
            + "<if test='operatorId != null'>AND operator_id = #{operatorId} </if>"
            + "<if test='dateFrom != null'>AND occurred_at &gt;= #{dateFrom} </if>"
            + "<if test='dateToBefore != null'>AND occurred_at &lt; #{dateToBefore} </if>"
            + "</where>"
            + "ORDER BY occurred_at "
            + "<choose><when test='ascending'>ASC</when><otherwise>DESC</otherwise></choose>, id "
            + "<choose><when test='ascending'>ASC</when><otherwise>DESC</otherwise></choose> "
            + "LIMIT #{offset}, #{limit}"
            + "</script>")
    List<AuditLogEntity> selectAuditLogPage(@Param("bizType") String bizType,
                                            @Param("bizId") Long bizId,
                                            @Param("action") String action,
                                            @Param("result") String result,
                                            @Param("operatorId") Long operatorId,
                                            @Param("dateFrom") java.time.LocalDateTime dateFrom,
                                            @Param("dateToBefore") java.time.LocalDateTime dateToBefore,
                                            @Param("ascending") boolean ascending,
                                            @Param("offset") long offset,
                                            @Param("limit") int limit);

    /**
     * 统计满足同样筛选条件的审计日志总数（配合 {@link #selectAuditLogPage} 实现分页）。
     *
     * <p>筛选条件与分页查询<b>逐条对应</b>（否则 {@code total} 与 {@code list} 会自相矛盾）。
     * 团队隔离同样由租户线自动追加 {@code team_id}，<b>不</b>加 {@code @InterceptorIgnore}。
     *
     * @return 命中条数（无记录时为 0）
     */
    @Select("<script>"
            + "SELECT COUNT(*) FROM audit_logs "
            + "<where>"
            + "<if test='bizType != null'>AND biz_type = #{bizType} </if>"
            + "<if test='bizId != null'>AND biz_id = #{bizId} </if>"
            + "<if test='action != null'>AND action = #{action} </if>"
            + "<if test='result != null'>AND result = #{result} </if>"
            + "<if test='operatorId != null'>AND operator_id = #{operatorId} </if>"
            + "<if test='dateFrom != null'>AND occurred_at &gt;= #{dateFrom} </if>"
            + "<if test='dateToBefore != null'>AND occurred_at &lt; #{dateToBefore} </if>"
            + "</where>"
            + "</script>")
    long selectAuditLogCount(@Param("bizType") String bizType,
                             @Param("bizId") Long bizId,
                             @Param("action") String action,
                             @Param("result") String result,
                             @Param("operatorId") Long operatorId,
                             @Param("dateFrom") java.time.LocalDateTime dateFrom,
                             @Param("dateToBefore") java.time.LocalDateTime dateToBefore);
}
