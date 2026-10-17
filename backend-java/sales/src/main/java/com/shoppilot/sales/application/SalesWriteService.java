package com.shoppilot.sales.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.shoppilot.audit.application.AuditWriteService;
import com.shoppilot.audit.domain.model.AuditAction;
import com.shoppilot.audit.domain.model.AuditEvent;
import com.shoppilot.audit.domain.model.AuditResult;
import com.shoppilot.catalog.application.SkuQueryService;
import com.shoppilot.catalog.application.WarehouseQueryService;
import com.shoppilot.catalog.infrastructure.persistence.entity.SkuEntity;
import com.shoppilot.common.context.TeamContext;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.ApiErrorDetail;
import com.shoppilot.common.web.BizException;
import com.shoppilot.sales.application.dto.SalesDailyResponse;
import com.shoppilot.sales.application.dto.SalesDailyUpsertRow;
import com.shoppilot.sales.infrastructure.persistence.entity.SalesDailyEntity;
import com.shoppilot.sales.infrastructure.persistence.mapper.SalesDailyMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 销售写入服务（{@code MODULE_BOUNDARIES §2.6}：{@code upsertDaily} / {@code batchUpsert}）。
 *
 * <h2>upsert 语义（{@code §6-D.2 U-6}：覆盖，非累加）</h2>
 * <p>业务键 = {@code (team_id, warehouse_id, sku_id, sale_date)}（{@code uk_sales_team_wh_sku_date}）。
 * 同键再次写入 <b>不报错、覆盖</b>：行数仍为 1，{@code qty} 为新值（"最后一次写入为准"）。
 * 实现用「先查后写 + 唯一约束兜底」（{@code DuplicateKeyException} 转更新），<b>不</b>使用
 * {@code @InterceptorIgnore}（B7 的 {@code InventoryPolicyService} 同一范式，{@code R46} 正确实践）。
 *
 * <h2>覆盖必须能把列写成 NULL</h2>
 * <p>更新走 {@link LambdaUpdateWrapper#set} 显式写每一列（含 {@code null}）——若用实体 {@code updateById}，
 * MyBatis-Plus 默认 {@code NOT_NULL} 策略会<b>跳过</b> {@code null} 字段，导致「用 null 覆盖旧值」失效
 * （与 U-6 覆盖语义冲突）。
 *
 * <h2>团队隔离（{@code §5.11}）</h2>
 * <p>落库前经 {@link SkuQueryService#getSku} / {@link WarehouseQueryService#requireInTeam}（均<b>受租户线约束</b>）
 * 校验 SKU / 仓库属于当前团队，跨团队 / 不存在 → {@code SAL-6005}（{@code 422}）；
 * {@code product_id} 由 {@code skus.product_id} 派生（保证「冗余列永远正确」）。
 *
 * <h2>审计（{@code DATABASE_DESIGN §6-J.1 #17}，{@code IMPORT_WORKFLOW:265}）</h2>
 * <p>每次 upsert 写一条 {@code SALES_DAILY_UPSERT}（{@code biz_type=SALES_DAILY}，{@code result=SUCCESS}），
 * {@code change_summary} 记录<b>被覆盖行数</b>——为免重蹈 {@code R66}（B7 策略 upsert 无审计）。
 * 审计经 {@link AuditWriteService#record} 旁路写入，失败不影响业务。
 */
@Service
public class SalesWriteService {

    public static final String SOURCE_IMPORT = "IMPORT";
    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_PLATFORM = "PLATFORM";

    private static final Set<String> ALLOWED_SOURCES = Set.of(SOURCE_IMPORT, SOURCE_MANUAL, SOURCE_PLATFORM);
    private static final String AUDIT_BIZ_TYPE = "SALES_DAILY";

    private final SalesDailyMapper salesDailyMapper;
    private final SkuQueryService skuQueryService;
    private final WarehouseQueryService warehouseQueryService;
    private final AuditWriteService auditWriteService;

    public SalesWriteService(SalesDailyMapper salesDailyMapper,
                             SkuQueryService skuQueryService,
                             WarehouseQueryService warehouseQueryService,
                             AuditWriteService auditWriteService) {
        this.salesDailyMapper = salesDailyMapper;
        this.skuQueryService = skuQueryService;
        this.warehouseQueryService = warehouseQueryService;
        this.auditWriteService = auditWriteService;
    }

    // =====================================================================
    // 单条 upsert（§2.6 对外接口；手工端点的核心路径）
    // =====================================================================

    /**
     * 单条日销售 upsert（覆盖语义）。
     *
     * @param skuId         SKU（必填）
     * @param warehouseId   仓库（必填）
     * @param saleDate      业务日期（必填）
     * @param qty           净销售数量（可空→0；为负→{@code SAL-6002}）
     * @param amount        销售金额（可空）
     * @param idempotencyKey 幂等键（写审计 / 追溯用；覆盖由业务键决定）
     * @return 落库后的行视图
     */
    @Transactional
    public SalesDailyResponse upsertDaily(Long skuId, Long warehouseId, LocalDate saleDate,
                                          BigDecimal qty, BigDecimal amount, String idempotencyKey) {
        SalesDailyUpsertRow row = new SalesDailyUpsertRow(
                skuId, warehouseId, saleDate, qty, BigDecimal.ZERO, amount,
                null, SOURCE_MANUAL, null, idempotencyKey);
        return doBatchUpsert(List.of(row)).get(0);
    }

    // =====================================================================
    // 批量 upsert（importdata 后续调用的入口；手工端点亦复用）
    // =====================================================================

    /**
     * 批量日销售 upsert。逐行校验 → 逐行 upsert（覆盖）→ 一条审计（记覆盖行数）。
     *
     * @param rows 待写入行（团队 / product_id 由服务端派生）
     * @return 与入参同序的落库视图
     */
    @Transactional
    public List<SalesDailyResponse> batchUpsert(List<SalesDailyUpsertRow> rows) {
        return doBatchUpsert(rows);
    }

    // =====================================================================
    // 内部
    // =====================================================================

    private List<SalesDailyResponse> doBatchUpsert(List<SalesDailyUpsertRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        // 1) 逐行校验（参数 + 团队隔离 + product_id 派生）——先全部校验，避免半批次脏写
        List<ValidatedRow> validated = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            validated.add(validate(rows.get(i), i));
        }

        Long operatorId = currentOperatorId();
        int overwritten = 0;
        List<Long> ids = new ArrayList<>(validated.size());
        Set<String> sources = new TreeSet<>();

        // 2) 逐行 upsert（先查后写 + 唯一约束兜底）
        for (ValidatedRow vr : validated) {
            SalesDailyEntity existing = selectByBusinessKey(vr.skuId(), vr.warehouseId(), vr.saleDate());
            Long id;
            boolean existed;
            if (existing == null) {
                SalesDailyEntity entity = newEntity(vr, operatorId);
                try {
                    salesDailyMapper.insert(entity);
                    id = entity.getId();
                    existed = false;
                } catch (DuplicateKeyException dke) {
                    // 并发首次 upsert：唯一约束兜底，转更新
                    SalesDailyEntity race = selectByBusinessKey(vr.skuId(), vr.warehouseId(), vr.saleDate());
                    if (race == null) {
                        throw dke;
                    }
                    updateMutable(race.getId(), vr, operatorId);
                    id = race.getId();
                    existed = true;
                }
            } else {
                updateMutable(existing.getId(), vr, operatorId);
                id = existing.getId();
                existed = true;
            }
            if (existed) {
                overwritten++;
            }
            ids.add(id);
            sources.add(vr.source());
        }

        // 3) 重新读取（取 DB 生成的 created_at/updated_at 与最终值），批量补 sku_code
        List<SalesDailyEntity> fresh = new ArrayList<>(ids.size());
        for (Long id : ids) {
            SalesDailyEntity e = salesDailyMapper.selectById(id);
            if (e != null) {
                fresh.add(e);
            }
        }
        Map<Long, String> skuCodes = skuCodesOf(fresh.stream()
                .map(SalesDailyEntity::getSkuId).collect(Collectors.toSet()));
        List<SalesDailyResponse> responses = fresh.stream()
                .map(e -> SalesDailyResponse.of(e, skuCodes.get(e.getSkuId())))
                .toList();

        // 4) 审计（一条，记覆盖行数；旁路写入失败不影响业务）
        recordAudit(overwritten, validated.size(), sources);
        return responses;
    }

    /** 逐行校验；非法即抛对应错误码（参数 {@code COM-0001} / {@code SAL-6002} / {@code SAL-6003} / {@code SAL-6005}）。 */
    private ValidatedRow validate(SalesDailyUpsertRow row, int index) {
        if (row == null) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(ApiErrorDetail.of("rows[" + index + "]", "行不能为空")));
        }
        List<ApiErrorDetail> missing = new ArrayList<>();
        if (row.skuId() == null) {
            missing.add(ApiErrorDetail.of("sku_id", "sku_id 必填"));
        }
        if (row.warehouseId() == null) {
            missing.add(ApiErrorDetail.of("warehouse_id", "warehouse_id 必填"));
        }
        if (!missing.isEmpty()) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败", missing);
        }
        if (row.saleDate() == null) {
            throw BizException.withDetails(ErrorCodes.SAL_6003, "sale_date 不合法",
                    List.of(ApiErrorDetail.of("sale_date", "sale_date 必填，格式 yyyy-MM-dd")));
        }
        BigDecimal qty = row.qty() == null ? BigDecimal.ZERO : row.qty();
        if (qty.signum() < 0) {
            throw BizException.withDetails(ErrorCodes.SAL_6002, "净销量不能为负",
                    List.of(new ApiErrorDetail("qty", "qty 不能为负", qty)));
        }
        BigDecimal returnQty = row.returnQty() == null ? BigDecimal.ZERO : row.returnQty();
        if (returnQty.signum() < 0) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(new ApiErrorDetail("return_qty", "return_qty 不能为负", returnQty)));
        }
        if (row.salesAmount() != null && row.salesAmount().signum() < 0) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(new ApiErrorDetail("sales_amount", "sales_amount 不能为负", row.salesAmount())));
        }
        if (row.orderCount() != null && row.orderCount() < 0) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(new ApiErrorDetail("order_count", "order_count 不能为负", row.orderCount())));
        }
        String source = (row.source() == null || row.source().isBlank())
                ? SOURCE_IMPORT : row.source().trim().toUpperCase();
        if (!ALLOWED_SOURCES.contains(source)) {
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(new ApiErrorDetail("source", "source 仅支持 IMPORT / MANUAL / PLATFORM", row.source())));
        }

        // 团队隔离：受租户线约束的查询成功 ⇒ SKU 属于当前团队；null ⇒ 跨团队 / 不存在
        SkuEntity sku = skuQueryService.getSku(row.skuId());
        if (sku == null) {
            throw BizException.withDetails(ErrorCodes.SAL_6005, "SKU 不属于当前团队",
                    List.of(new ApiErrorDetail("sku_id", "SKU 不存在或不属于当前团队", row.skuId())));
        }
        try {
            warehouseQueryService.requireInTeam(row.warehouseId());
        } catch (BizException be) {
            // 跨团队 → TEAM-2001 / 不存在 → CAT-3001，统一收敛为 SAL-6005（SKU/仓库须同团队）
            throw BizException.withDetails(ErrorCodes.SAL_6005, "仓库不属于当前团队",
                    List.of(new ApiErrorDetail("warehouse_id", "仓库不存在或不属于当前团队", row.warehouseId())));
        }
        // product_id 由 skus.product_id 派生（不可变冗余列，§5.11 设计要点）
        return new ValidatedRow(row.skuId(), row.warehouseId(), row.saleDate(), qty, returnQty,
                row.salesAmount(), row.orderCount(), source, row.batchId(), sku.getProductId());
    }

    private SalesDailyEntity selectByBusinessKey(Long skuId, Long warehouseId, LocalDate saleDate) {
        return salesDailyMapper.selectOne(new LambdaQueryWrapper<SalesDailyEntity>()
                .eq(SalesDailyEntity::getSkuId, skuId)
                .eq(SalesDailyEntity::getWarehouseId, warehouseId)
                .eq(SalesDailyEntity::getSaleDate, saleDate)
                .last("LIMIT 1"));
    }

    private SalesDailyEntity newEntity(ValidatedRow vr, Long operatorId) {
        SalesDailyEntity e = new SalesDailyEntity();
        // team_id 不显式设置：由租户插件在 INSERT 时自动补列（同 InventoryPolicyService）
        e.setSkuId(vr.skuId());
        e.setWarehouseId(vr.warehouseId());
        e.setProductId(vr.productId());
        e.setSaleDate(vr.saleDate());
        e.setQty(vr.qty());
        e.setReturnQty(vr.returnQty());
        e.setSalesAmount(vr.salesAmount());
        e.setOrderCount(vr.orderCount());
        e.setSource(vr.source());
        e.setBatchId(vr.batchId());
        e.setCreatedBy(operatorId);
        e.setUpdatedBy(operatorId);
        return e;
    }

    /** 覆盖更新：显式 set 每一列（含 null），确保「用 null 覆盖旧值」生效（U-6 覆盖语义）。 */
    private void updateMutable(Long id, ValidatedRow vr, Long operatorId) {
        salesDailyMapper.update(null, new LambdaUpdateWrapper<SalesDailyEntity>()
                .eq(SalesDailyEntity::getId, id)
                .set(SalesDailyEntity::getProductId, vr.productId())
                .set(SalesDailyEntity::getQty, vr.qty())
                .set(SalesDailyEntity::getReturnQty, vr.returnQty())
                .set(SalesDailyEntity::getSalesAmount, vr.salesAmount())
                .set(SalesDailyEntity::getOrderCount, vr.orderCount())
                .set(SalesDailyEntity::getSource, vr.source())
                .set(SalesDailyEntity::getBatchId, vr.batchId())
                .set(SalesDailyEntity::getUpdatedBy, operatorId));
    }

    private void recordAudit(int overwritten, int total, Set<String> sources) {
        TeamContext.Snapshot snap = TeamContext.get();
        if (snap == null || snap.teamId() == null) {
            // 无团队上下文（理论上 REST 路径不会发生）则不写审计，避免造无主审计行
            return;
        }
        String sourceSummary = sources.isEmpty() ? "-" : String.join(",", sources);
        auditWriteService.record(AuditEvent.builder()
                .teamId(snap.teamId())
                .action(AuditAction.SALES_DAILY_UPSERT)
                .bizType(AUDIT_BIZ_TYPE)
                .operatorId(snap.userId())
                .operatorName(snap.username())
                .operatorRole(snap.roles().isEmpty() ? null : snap.roles().get(0))
                .result(AuditResult.SUCCESS)
                .changeSummary("upsert 日销售：覆盖 " + overwritten + " 行 / 共 " + total
                        + " 行（source=" + sourceSummary + "）")
                .build());
    }

    private Long currentOperatorId() {
        TeamContext.Snapshot snapshot = TeamContext.get();
        return snapshot == null ? null : snapshot.userId();
    }

    private Map<Long, String> skuCodesOf(Set<Long> skuIds) {
        if (skuIds.isEmpty()) {
            return Map.of();
        }
        return skuQueryService.getCodesByIds(new ArrayList<>(skuIds)).stream()
                .collect(Collectors.toMap(SkuEntity::getId, SkuEntity::getSkuCode,
                        (a, b) -> a, LinkedHashMap::new));
    }

    /** 校验通过后的行（含服务端派生的 product_id 与规范化后的 source）。 */
    private record ValidatedRow(Long skuId, Long warehouseId, LocalDate saleDate,
                                BigDecimal qty, BigDecimal returnQty, BigDecimal salesAmount,
                                Integer orderCount, String source, Long batchId, Long productId) {
    }
}
