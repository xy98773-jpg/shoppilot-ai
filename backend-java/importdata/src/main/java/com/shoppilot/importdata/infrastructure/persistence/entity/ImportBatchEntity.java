package com.shoppilot.importdata.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * {@code import_batches} 表映射（{@code DATABASE_DESIGN §5.12} / 迁移 {@code V1.0.5__importdata.sql}）。
 *
 * <p>含 {@code team_id} → 被 {@code TenantLineInnerInterceptor} 自动追加
 * {@code AND team_id = <当前团队>}（SELECT/UPDATE/DELETE），INSERT 由插件补齐 team_id 列
 * （本层仍在服务端显式 set 双保险，{@code DESIGN_BASIS §6} 规则 4/5）。
 *
 * <p><b>审计字段（集合 A）</b>：{@code createdAt} / {@code updatedAt} 由 DB 默认值维护，
 * 应用层不显式覆盖；{@code createdBy} / {@code updatedBy} 由服务层从 {@code TeamContext} 显式写入。
 * <b>无 version</b>（{@code §7.4}：状态守卫 + 同批次单执行者）。
 *
 * <p><b>M3-T02 范围</b>：本批只写「步骤 1-3」涉及的列（文件与幂等），
 * 解析/映射/落库相关列（{@code totalRows} 语义累加、{@code fieldMappingJson}、
 * {@code parseOptionsJson}、{@code startedAt}、{@code finishedAt} 等）留待 M3-T03 起写入。
 */
@Data
@TableName("import_batches")
public class ImportBatchEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 团队 ID（隔离边界，不可变；只来自服务端 {@code TeamContext}）。 */
    private Long teamId;

    /** 批次号 {@code IMP-{yyyyMMdd}-{6 位序号}}（业务编号，不可变）。 */
    private String batchNo;

    /** {@link com.shoppilot.importdata.domain.model.ImportBizType}（6 值之一，不可变）。 */
    private String bizType;

    /** 原始文件名（仅展示，<b>不作幂等依据</b>）。 */
    private String fileName;

    /** 文件内容 SHA-256（十六进制小写，不可变）。 */
    private String fileHash;

    /**
     * 幂等槽位列：占用时 = {@code fileHash}，释放时 = {@code NULL}。
     *
     * <p>唯一键 {@code uk_imp_team_hash_biz} 建在本列（R31 裁定），
     * 故「命中重复」的 {@code DUPLICATE_REJECTED} 记录必须置 {@code NULL} 才能插入。
     */
    private String dedupeHash;

    /** 文件字节数。 */
    private Long fileSize;

    /** 原始文件存储路径（文件是全量原文的唯一真相）。 */
    private String storagePath;

    /** {@link com.shoppilot.importdata.domain.model.ImportBatchStatus}（10 值之一）。 */
    private String status;

    /** 数据行总数（不含表头；本批恒为 0，PARSED 后固定）。 */
    private Integer totalRows;

    /** 成功落库行数（本批恒为 0）。 */
    private Integer successRows;

    /** 失败行数（本批恒为 0）。 */
    private Integer failedRows;

    /** 同一文件的第几次导入尝试（≥1）。 */
    private Integer attemptNo;

    /** 字段映射 JSON（M3-T04 起写入）。 */
    private String fieldMappingJson;

    /** 解析选项 JSON（M3-T03 起写入）。 */
    private String parseOptionsJson;

    /** 模板版本（诊断用，可空）。 */
    private String templateVersion;

    /** 批次级错误码（本批不写）。 */
    private String errorCode;

    /** 批次级错误说明（本批不写）。 */
    private String errorMessage;

    /** 命中重复时指向的已存在批次（逻辑自关联，不建 FK）。 */
    private Long duplicateOfBatchId;

    /** 被哪一批次取代（逻辑自关联，不建 FK；M3-T11 强制重导写入）。 */
    private Long supersededByBatchId;

    /** 开始落库时间（UTC；M3-T06 起写入）。 */
    private LocalDateTime startedAt;

    /** 结束时间（UTC；M3-T06 起写入）。 */
    private LocalDateTime finishedAt;

    // ---- 审计字段（集合 A）；无 version（§7.4）----
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long createdBy;
    private Long updatedBy;
}
