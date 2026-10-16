package com.shoppilot.importdata.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * {@code import_row_errors} 表映射（{@code DATABASE_DESIGN §5.13} / 迁移 {@code V1.0.5__importdata.sql}）。
 *
 * <p>含 {@code team_id} → 被 {@code TenantLineInnerInterceptor} 自动追加
 * {@code AND team_id = <当前团队>}；INSERT 由插件补齐 team_id（本层仍显式 set 双保险，
 * {@code DESIGN_BASIS §6} 规则 4/5）。
 *
 * <p><b>审计字段（集合 B）</b>：{@code createdAt} 由 DB 默认值维护，{@code createdBy} 由服务层从
 * {@code TeamContext} 显式写入；<b>无 {@code updated_*}、无 {@code version}</b>。
 *
 * <p><b>更新规则（§5.13）</b>：<b>仅 INSERT</b>，不 UPDATE / DELETE（批次取消也不删错误行，历史可查）。
 * <b>无唯一约束</b>：同一行可命中多条规则，强制唯一会丢弃诊断信息（§8）。
 */
@Data
@TableName("import_row_errors")
public class ImportRowErrorEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 团队 ID（隔离边界，只来自服务端 {@code TeamContext}）。 */
    private Long teamId;

    /** 所属批次（{@code fk_ire_batch → import_batches(id)}，RESTRICT）。 */
    private Long batchId;

    /** 数据行号（1 起，不含表头；{@code ck_ire_row_no >= 1}）。 */
    private Integer rowNo;

    /**
     * 错误级别：{@code ERROR}（阻断该行）/ {@code WARNING}（仅提示）。
     *
     * <p>受 {@code ck_ire_level} 约束（{@code ERROR,WARNING}）；默认值 {@code ERROR}。
     */
    private String errorLevel;

    /** 错误码常量（{@code COM-0001} / {@code CAT-3001} / {@code SAL-6002} / {@code IMP-4006} …）。 */
    private String errorCode;

    /** 出错字段名（多字段错误取首个；可空）。 */
    private String errorField;

    /** 可读错误说明（中文，≤500 字）。 */
    private String errorMessage;

    /**
     * <b>仅失败行</b>的原始行数据（JSON，含<b>未映射列</b>，§6-E.2 / §9.3）。
     *
     * <p>理由：① 体积与失败数成正比；② 精确对应用户需要修正的行；③ 原文唯一真相是
     * {@code import_batches.storage_path}，本列只是便利副本。
     */
    private String rawData;

    // ---- 审计字段（集合 B）；无 version ----
    private LocalDateTime createdAt;
    private Long createdBy;
}
