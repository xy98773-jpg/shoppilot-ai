package com.shoppilot.analysis.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shoppilot.analysis.infrastructure.persistence.entity.AnalysisRunEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code analysis_runs} 表 Mapper（{@code DESIGN_BASIS.md §5.14} / 迁移 {@code V1.0.9}）。
 *
 * <p>普通 CRUD 走 {@link BaseMapper}；租户线自动追加 {@code AND team_id = <当前团队>}
 * （SELECT/UPDATE/DELETE）并在 INSERT 时补 {@code team_id}（从 {@code TeamContext} 取）。
 *
 * <p><b>本 Mapper 不含任何 {@code @InterceptorIgnore}</b>（与 {@code SalesDailyMapper} 同一范式，
 * {@code R46} 正确实践）：createRun 的「幂等槽位」「并发判定」「run_no 序列」全部走
 * <b>带租户线</b>的 BaseMapper 查询 + 唯一约束兜底（{@code uk_run_team_idem} /
 * {@code uk_run_team_no}），不需要也<b>不允许</b>绕过租户隔离。
 *
 * <p><b>不写自定义 SQL</b>：三个查询（幂等槽位查找 / 进行中集合 / 日内序列计数）均为
 * 等值与 {@code LIKE} 前缀匹配，用 {@code LambdaQueryWrapper} 表达更直接，
 * 也避免 {@code ${}} 拼接面（{@code run_no} 前缀是服务端生成的常量形态，无客户端输入）。
 */
@Mapper
public interface AnalysisRunMapper extends BaseMapper<AnalysisRunEntity> {
}
