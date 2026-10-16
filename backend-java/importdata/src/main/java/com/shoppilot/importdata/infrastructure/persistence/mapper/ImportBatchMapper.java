package com.shoppilot.importdata.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shoppilot.importdata.infrastructure.persistence.entity.ImportBatchEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code import_batches} 表 Mapper。
 *
 * <p>普通 CRUD 走 {@link BaseMapper}，租户线自动追加 {@code AND team_id = <当前团队>}。
 *
 * <p><b>M3-T02 范围</b>：仅「文件上传 + 哈希 + 文件级幂等」所需的查询；
 * 解析/映射/落库相关查询属 M3-T03 起。
 */
@Mapper
public interface ImportBatchMapper extends BaseMapper<ImportBatchEntity> {

    /**
     * 取同团队同日期的<b>最大批次序号</b>（{@code batch_no} 末段数字）。
     *
     * <p>SQL 显式携带 {@code team_id = #{teamId}}（团队来自服务端 {@code TeamContext}，
     * 绝不接受客户端传入，{@code DESIGN_BASIS §6} 规则 3）；租户线会再追加一次
     * {@code team_id = <当前团队>} 作为兜底（规则 4/5 双保险）。
     *
     * <p>{@code batch_no} 形如 {@code IMP-20261014-000001}，末段即当日 6 位序号；
     * 无同日批次时 {@code MAX} 为 {@code NULL}，由 {@code COALESCE} 兜底为 0。
     *
     * @param teamId 当前团队（服务端）
     * @param day    {@code yyyyMMdd} 日期串（UTC）
     * @return 当日最大序号；无记录返回 0
     */
    @Select("SELECT COALESCE(MAX(CAST(SUBSTRING_INDEX(batch_no, '-', -1) AS UNSIGNED)), 0) "
            + "FROM import_batches WHERE team_id = #{teamId} AND batch_no LIKE CONCAT('IMP-', #{day}, '-%')")
    Long selectMaxDailySeq(@Param("teamId") Long teamId, @Param("day") String day);
}
