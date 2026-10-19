package com.shoppilot.analysis.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * {@code analysis_items} 表映射（{@code DATABASE_DESIGN.md §5.15}；迁移 {@code V1.0.9__analysis.sql}）。
 *
 * <h2>★ 仅 INSERT 的不可变快照（{@code AI_SERVICE_CONTRACT.md §7} 落库动作 1）</h2>
 * <p>本表<b>没有</b> {@code updated_at} / {@code updated_by} / {@code version}（审计集合 B，
 * {@code DATA_DICTIONARY §4.1}）——它是「某次分析在某时刻算出的结论」，<b>只追加、不修订</b>。
 * 需要改结论 = 需要重跑分析生成新 run，而不是 UPDATE 老快照。
 * 因此<b>禁止</b>在本表上调用任何 UPDATE / DELETE 方法；若发现需要修改，说明设计错了，停下报告。
 *
 * <h2>★ 三列必须由 Java 回读主数据（Python 不返回）</h2>
 * <ul>
 *   <li>{@code productId} ← {@code skus.product_id}（{@code R132}）：Python 的 {@code ResultItem}
 *       无商品维度；不返回就不写会导致列 {@code NOT NULL} 落库失败。</li>
 *   <li>{@code inTransitQty} ← {@code inventory_balances.in_transit_qty}（{@code R132}）：
 *       与出站的 {@code effective_in_transit_qty} <b>口径不同</b>——前者是余额列原值（R124 临时口径），
 *       后者是本次分析使用的有效在途。M6 换回 {@code §10.4} 时两者要<b>一并</b>换。</li>
 *   <li>{@code salesWindowDays} ← 请求 {@code params.window_days}（{@code R132}）：
 *       统计窗口 N 是<b>请求参数</b>，不是计算结果。</li>
 * </ul>
 *
 * <h2>★ {@code availableQty} 的红线（铁律 {@code R67}）</h2>
 * <p>{@code available_qty = on_hand − committed}，<b>仅作展示口径</b>：绝对禁止进入
 * {@code Q_base} / {@code IP} / 任何补货计算。Python 侧的规则层用 {@code inventory_position}
 * （含有效在途）算补货量，与本列无关。
 *
 * <p><b>{@code inventoryPositionQty} 允许负值</b>（超卖；{@code R118}）：表列是有符号
 * {@code DECIMAL(18,3)}，且契约 §2.1 对它只要求「与三项一致」、未要求 {@code >= 0}。
 *
 * <p><b>{@code team_id} 不接受客户端传入</b>：只从 {@code TeamContext}（HTTP 路径）或
 * {@code analysis_runs.team_id}（定时任务路径）注入；本表不在
 * {@code TenantTables.WITHOUT_TEAM_ID} 黑名单，由租户插件在 INSERT 时补值。
 *
 * <p>JSON 列以 {@link String} 承载，入库写<b>规范化文本</b>（与 {@code analysis_runs}
 * 的 JSON 列同口径）：{@code riskTypes} / {@code riskDetailJson} / {@code dataQualityJson} /
 * {@code llmDetailJson}。★ 三段 JSON 语义<b>不得混装</b>（{@code §8} 细化规则 2）：
 * LLM 状态只进 {@code llmDetailJson}。
 */
@Data
@TableName("analysis_items")
public class AnalysisItemEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 团队 ID（服务端注入；不可变）。 */
    private Long teamId;

    /** 所属运行（{@code fk_ai_run}；不可变）。 */
    private Long runId;

    /** SKU（{@code fk_ai_sku}；不可变）。 */
    private Long skuId;

    /** 冗余商品 ID（Python 不返回，Java 回读 {@code skus.product_id}；不可变）。 */
    private Long productId;

    /** 仓库（{@code fk_ai_warehouse}；不可变）。 */
    private Long warehouseId;

    /** 快照：在手库存（无默认值，必须显式写入）。 */
    private BigDecimal onHandQty;

    /** 快照：在途库存（Python <b>不</b>返回，Java 回读余额列；R124 临时口径）。 */
    private BigDecimal inTransitQty;

    /** 快照：有效在途（item 回显本次分析使用的值）。 */
    private BigDecimal effectiveInTransitQty;

    /** 快照：已承诺。 */
    private BigDecimal committedQty;

    /** 快照：IP = 在手 + 有效在途 − 已承诺（<b>可为负</b>，R118）。 */
    private BigDecimal inventoryPositionQty;

    /** 快照：available = 在手 − 已承诺（<b>仅展示口径，禁入 Q_base/IP</b>，R67）。 */
    private BigDecimal availableQty;

    /** D（{@code §10.1}）；数据不足为 {@code null}。 */
    private BigDecimal avgDailySales;

    /** 统计窗口 N（请求 {@code params.window_days}；Python 不返回，R132）。 */
    private Integer salesWindowDays;

    /** 有效销售天数 n。 */
    private Integer validDays;

    /** 窗口内总销量。 */
    private BigDecimal totalSalesQty;

    /** 波动率 CV（★ 精度 4 位，与其余数量列的 3 位不同；{@code mean=0} 时为 {@code null}）。 */
    private BigDecimal cv;

    /** 采用交期 L。 */
    private Integer leadTimeDays;

    /** L 来源（{@code LeadTimeSource} 四值；CHECK 允许 NULL）。 */
    private String leadTimeSource;

    /** 覆盖天数 R。 */
    private Integer coverageDays;

    /** 采用 SS（{@code §10.3}）。 */
    private BigDecimal safetyStockQty;

    /** 是否因高波动上浮 50%。 */
    private Boolean volatilityAdjusted;

    /** {@code Q_base}。 */
    private BigDecimal qtyBase;

    /** {@code Q_suggest}。 */
    private BigDecimal qtySuggest;

    /** 生效 MOQ。 */
    private BigDecimal moqApplied;

    /** 生效采购倍数。 */
    private BigDecimal orderMultipleApplied;

    /** 是否因预算被截断。 */
    private Boolean truncatedByBudget;

    /** 风险等级（{@code RiskLevel} 五值；{@code NOT NULL DEFAULT 'NONE'}）。 */
    private String riskLevel;

    /** RiskType 数组（JSON 列；去重后的规范文本）。 */
    private String riskTypes;

    /** 命中规则明细（JSON 列；{@code §8} 规则 2：不得混入 LLM 或数据质量内容）。 */
    private String riskDetailJson;

    /** 数据质量检查结果（JSON 列：{@code {"grade":...,"warnings":[...]}）。 */
    private String dataQualityJson;

    /** LLM 解释（降级为 {@code null}；落库前截断 2000 字符，见 {@code §7} 第 15 项）。 */
    private String llmExplanation;

    /** 条目级 LLM 明细（JSON 列：{@code {"llm_status":...,"evidence":[...]}；降级可为 NULL）。 */
    private String llmDetailJson;

    // ---- 审计字段（集合 B：DATA_DICTIONARY §4.1；★ 无 updated_* / version） ----
    private LocalDateTime createdAt;
    private Long createdBy;
}