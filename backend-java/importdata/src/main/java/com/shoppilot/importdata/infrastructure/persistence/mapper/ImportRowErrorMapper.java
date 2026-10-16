package com.shoppilot.importdata.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shoppilot.importdata.infrastructure.persistence.entity.ImportRowErrorEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code import_row_errors} 表 Mapper。
 *
 * <p>普通 CRUD 走 {@link BaseMapper}，租户线自动追加 {@code AND team_id = <当前团队>}。</p>
 *
 * <p><b>只 INSERT</b>（{@code DATABASE_DESIGN §5.13} 更新规则）：
 * 错误行是诊断证据，批次取消也不删除；重复校验同一批次会<b>追加</b>而非覆盖
 * （见 {@code ImportValidationService}：校验前先清理本批次的旧错误行，避免重复堆叠）。</p>
 *
 * <p><b>分页查询</b>：{@link #selectPageByBatch} / {@link #countByBatch} 供
 * {@code GET /{id}/errors} 使用。租户线对 {@code @Select} 同样生效（本类不写
 * {@code @InterceptorIgnore(tenantLine="true")}），故<b>无需手动拼 {@code team_id}</b>，
 * 跨团队/不存在的批次由 {@code ImportBatchController} 的 {@code loadOwned} 先行拦下。</p>
 */
@Mapper
public interface ImportRowErrorMapper extends BaseMapper<ImportRowErrorEntity> {

    /**
     * 按批次分页查询错误行（可按 {@code error_level} / {@code row_no} 过滤）。
     *
     * @param batchId    批次 ID（必填）
     * @param errorLevel 错误级别过滤（{@code ERROR}/{@code WARNING}），{@code null} 不过滤
     * @param rowNo      数据行号过滤，{@code null} 不过滤
     * @param offset     {@code LIMIT} 偏移
     * @param limit      {@code LIMIT} 行数
     */
    @Select("<script>"
            + "SELECT * FROM import_row_errors WHERE batch_id = #{batchId}"
            + "<if test='errorLevel != null'> AND error_level = #{errorLevel}</if>"
            + "<if test='rowNo != null'> AND row_no = #{rowNo}</if>"
            + " ORDER BY row_no ASC, id ASC LIMIT #{limit} OFFSET #{offset}"
            + "</script>")
    List<ImportRowErrorEntity> selectPageByBatch(@Param("batchId") Long batchId,
                                                @Param("errorLevel") String errorLevel,
                                                @Param("rowNo") Integer rowNo,
                                                @Param("offset") long offset,
                                                @Param("limit") long limit);

    /**
     * 与 {@link #selectPageByBatch} 同口径的计数（供分页元信息 {@code total}）。
     */
    @Select("<script>"
            + "SELECT COUNT(*) FROM import_row_errors WHERE batch_id = #{batchId}"
            + "<if test='errorLevel != null'> AND error_level = #{errorLevel}</if>"
            + "<if test='rowNo != null'> AND row_no = #{rowNo}</if>"
            + "</script>")
    long countByBatch(@Param("batchId") Long batchId,
                      @Param("errorLevel") String errorLevel,
                      @Param("rowNo") Integer rowNo);
}
