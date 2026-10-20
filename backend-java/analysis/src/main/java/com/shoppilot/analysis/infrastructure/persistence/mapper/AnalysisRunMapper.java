package com.shoppilot.analysis.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shoppilot.analysis.infrastructure.persistence.entity.AnalysisRunEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code analysis_runs} 表 Mapper（{@code DESIGN_BASIS.md §5.14} / 迁移 {@code V1.0.9}）。
 *
 * <p>普通 CRUD 走 {@link BaseMapper}；租户线自动追加 {@code AND team_id = <当前团队>}
 * （SELECT/UPDATE/DELETE）并在 INSERT 时补 {@code team_id}（从 {@code TeamContext} 取）。
 *
 * <h2>租户线例外：仅 {@link #selectInFlightAcrossTeams}（全仓第 14 处，{@code R133}）</h2>
 * <p>定时扫描<b>必然跨团队</b>：它跑在无 {@code TeamContext} 的后台线程里，若被租户线约束
 * 会退化成「拒绝查询」，扫描直接失效。因此这一个方法
 * {@code @InterceptorIgnore(tenantLine="true")}，并遵守该例外类型的两条硬约束：
 * <ol>
 *   <li><b>必须自带显式 SQL</b>——否则会落到 BaseMapper 的默认实现，而默认实现完全依赖租户线，
 *       一旦被 ignore，这个查询就<b>彻底没有任何团队隔离</b>
 *       （{@code M2_TenantIsolationDbIntegrationTest} 对此有专门断言）；</li>
 *   <li><b>只取 {@code id / team_id / status / timeout_at} 四列</b>，不带任何业务字段；
 *       逐条按 {@code team_id} 处理，不泄漏跨团队数据。与 {@code idx_run_timeout} 的
 *       {@code status} 最左例外同源。</li>
 * </ol>
 * <p>★ 新增任何 {@code @InterceptorIgnore} 方法都必须先登记进
 * {@code M2_TenantIsolationDbIntegrationTest.TENANT_LINE_EXCEPTIONS}（14 → 15 条），
 * 否则该测试必红——这正是「悄悄绕过租户线」被变成一次性可见失败的设计。
 *
 * <p><b>其余查询一律不写自定义 SQL</b>：幂等槽位 / 并发判定 / run_no 序列都走
 * <b>带租户线</b>的 BaseMapper 查询 + 唯一约束兜底（{@code uk_run_team_idem} /
 * {@code uk_run_team_no}），不需要也<b>不允许</b>绕过租户隔离（{@code R46} 正确实践）。
 */
@Mapper
public interface AnalysisRunMapper extends BaseMapper<AnalysisRunEntity> {

    /**
     * 全库扫描「进行中」运行（超时行优先），<b>绕过租户线</b>（{@code R133}）。
     *
     * <p>SQL 显式携带 {@code status IN ('PENDING','RUNNING')}，走
     * {@code idx_run_timeout (status, timeout_at)}；排序把
     * {@code timeout_at <= expiredBefore} 的行排在前面（{@code §5.38} 裁定 #4：
     * <b>超时优先</b>，避免给已死任务再发一次无谓的状态请求）。
     *
     * @param limit         本轮最多返回的行数（配置项 {@code scan-limit}，默认 50）
     * @param expiredBefore 「已超时」的判定基准（UTC）
     * @return 只含四列的轻量投影（{@code id / team_id / status / timeout_at}）
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT id, team_id, status, timeout_at FROM analysis_runs "
            + "WHERE status IN ('PENDING','RUNNING') AND timeout_at IS NOT NULL "
            + "ORDER BY (timeout_at <= #{expiredBefore}) DESC, timeout_at ASC "
            + "LIMIT #{limit}")
    List<ScanRow> selectInFlightAcrossTeams(@Param("limit") int limit,
                                           @Param("expiredBefore") LocalDateTime expiredBefore);

    /**
     * 扫描投影（<b>刻意不是实体</b>）：只含状态推进与超时判定必需的四个字段。
     *
     * <p>刻意<b>不</b>返回 {@code scopeJson} / {@code paramsJson} 等业务字段：
     * 跨团队扫描只需定位「谁超时了 / 谁还在跑」，其余字段在<b>按 {@code team_id} 逐条处理</b>时
     * 重新按该团队上下文查回（见 {@code AnalysisRunSyncService}）。
     * 这样即便扫描日志被误看，也拿不到任何业务内容。
     *
     * @param id        运行主键
     * @param teamId    所属团队（逐条处理时用它建立上下文）
     * @param status    当前状态（{@code PENDING} / {@code RUNNING}）
     * @param timeoutAt 超时截止（UTC）
     */
    record ScanRow(Long id, Long teamId, String status, LocalDateTime timeoutAt) {
    }
}