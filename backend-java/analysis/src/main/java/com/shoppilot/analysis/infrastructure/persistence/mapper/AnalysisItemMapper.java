package com.shoppilot.analysis.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shoppilot.analysis.infrastructure.persistence.entity.AnalysisItemEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code analysis_items} 表 Mapper（{@code DATABASE_DESIGN.md §5.15}）。
 *
 * <p><b>只读 + 批量插入</b>：本表是<b>仅 INSERT 的不可变快照</b>（无 {@code updated_at} /
 * {@code updated_by} / {@code version}），因此本 Mapper
 * <b>不提供、不应提供任何 UPDATE / DELETE 方法</b>。
 * 若将来出现「需要修正某条快照」的需求，正确做法是<b>重跑分析生成新 run</b>。
 *
 * <p>唯一约束 {@code uk_ai_run_sku_wh (run_id, sku_id, warehouse_id)} 是「重复 SKU」的
 * <b>最后兜底</b>：应用层已按 {@code §7} 第 13 项做过内存去重，若仍撞键说明去重逻辑有漏，
 * 此时<b>整批事务回滚</b>并把 run 置 {@code FAILED / ANL-7004}，
 * <b>不要</b>吞成「部分成功」——半份快照无法解释。
 *
 * <p><b>本 Mapper 不含任何 {@code @InterceptorIgnore}</b>：定时任务写入时由调用方显式
 * 设 {@code team_id}（来自 {@code analysis_runs}），租户线在 INSERT 时补值；
 * 查询一律带租户线，无受控例外。
 */
@Mapper
public interface AnalysisItemMapper extends BaseMapper<AnalysisItemEntity> {
}