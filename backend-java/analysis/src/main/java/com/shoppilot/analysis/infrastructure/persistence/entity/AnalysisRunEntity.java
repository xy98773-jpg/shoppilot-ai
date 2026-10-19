package com.shoppilot.analysis.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * {@code analysis_runs} 表映射（{@code DATABASE_DESIGN.md §5.14}；迁移 {@code V1.0.9__analysis.sql}）。
 *
 * <p>「输入快照依据」的载体：{@code scopeType} / {@code scopeJson} / {@code warehouseId} /
 * {@code paramsJson} / {@code algorithmVersion} / {@code promptVersion} / {@code modelName} /
 * {@code requestId} / {@code idempotencyKey} / {@code triggeredBy} 写入后<b>不可变</b>；
 * 执行期只允许更新进度与统计列。审计字段为<b>集合 A</b>（{@code DATA_DICTIONARY.md §4.1}，
 * {@code created_at}/{@code updated_at}/{@code created_by}/{@code updated_by}），
 * <b>无 {@code version}</b>——用状态守卫代替乐观锁（{@code DATABASE_DESIGN.md §7.4} 表 A）。
 *
 * <p><b>JSON 列以 {@link String} 承载</b>：MySQL JSON 列经 JDBC 返回文本；入库时由服务层写入
 * <b>规范化文本</b>（键序字典序、数值去尾零——见 {@code AnalysisScope#canonicalJson()} /
 * {@code AnalysisParams#canonicalJson()}），使「入库值 == 幂等比对基准」零偏差。
 *
 * <p><b>{@code team_id} 不接受客户端传入</b>（{@code API_CONTRACT.md §5.3}）：只从
 * {@code TeamContext} 注入；本表<b>不在</b> {@code TenantTables.WITHOUT_TEAM_ID} 黑名单，
 * 由租户插件在 INSERT 时补值、SELECT/UPDATE/DELETE 时追加条件
 * （{@code M2_TenantIsolationDbIntegrationTest} 守护）。
 *
 * <p>全部方法由 Lombok {@code @Data} 生成（与 sales/inventory 实体一致）。
 */
@Data
@TableName("analysis_runs")
public class AnalysisRunEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long teamId;

    /** 运行编号（团队内唯一 {@code uk_run_team_no}；D-25：{@code RUN-{yyyyMMdd}-{4 位日内序列}}；不可变）。 */
    private String runNo;

    /** 运行状态（{@code AnalysisRunStatus}；不可回退，终态不变）。 */
    private String status;

    /** 范围类型（{@code AnalysisScopeType}；不可变）。 */
    private String scopeType;

    /** 范围明细 JSON（{@code SELECTED_SKUS} 的 sku_ids / {@code CATEGORY} 的 category；不可变）。 */
    private String scopeJson;

    /** 限定仓库（{@code NULL} = 默认仓；逻辑关联 {@code warehouses.id}，不建外键；不可变）。 */
    private Long warehouseId;

    /** 运行参数 JSON（全量八字段规范化文本；不可变）。 */
    private String paramsJson;

    /** 算法版本（如 {@code avg-ma-3sigma-v1}；不可变）。 */
    private String algorithmVersion;

    /** Prompt 模板版本（如 {@code explain_risk.v1}；不可变）。 */
    private String promptVersion;

    /** LLM 模型名（不写密钥；不可变）。 */
    private String modelName;

    /** LLM 阶段状态（{@code LlmStatus}）。 */
    private String llmStatus;

    /** LLM 失败原因（{@code DEGRADED} 时非空）。 */
    private String llmErrorMessage;

    /** 输入销售数据最早日期（执行期写入后固定）。 */
    private LocalDate dataStartDate;

    /** 输入销售数据最晚日期（执行期写入后固定）。 */
    private LocalDate dataEndDate;

    /** 结果摘要 JSON（各风险等级 SKU 数、建议数、截断数；终态时写入）。 */
    private String summaryJson;

    /** 计划分析 SKU 数。 */
    private Integer totalSkus;

    /** 已完成 SKU 数。 */
    private Integer processedSkus;

    /** 存在风险的 SKU 数（终态时写入）。 */
    private Integer riskSkuCount;

    /** 生成补货建议数（终态时写入）。 */
    private Integer suggestionCount;

    /** 进度 0–100（{@code ck_run_progress}）。 */
    private Integer progress;

    /** 当前步骤名。 */
    private String currentStep;

    /** 失败错误码（{@code ANL-7002/7003/7004...}）。 */
    private String errorCode;

    /** 失败原因（对外脱敏文案，不含堆栈/SQL）。 */
    private String errorMessage;

    /** 链路追踪 {@code X-Request-Id}（不可变；非 HTTP 上下文为 {@code NULL}）。 */
    private String requestId;

    /** 创建幂等键（可空 ⇒ 多行 NULL 合法；本批必填，不可变）。 */
    private String idempotencyKey;

    /** 触发人（逻辑关联 {@code users.id}，不建外键；不可变）。 */
    private Long triggeredBy;

    /** 开始执行时间（{@code RUNNING} 时写入）。 */
    private LocalDateTime startedAt;

    /** 结束时间（终态时写入）。 */
    private LocalDateTime finishedAt;

    /** 超时截止（创建 + 300s，{@code DESIGN_BASIS.md §13.4}；不可变，B20 消费）。 */
    private LocalDateTime timeoutAt;

    // ---- 审计字段（集合 A：DATA_DICTIONARY §4.1；无 version） ----
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long createdBy;
    private Long updatedBy;
}
