package com.shoppilot.replenishment.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.ApiErrorDetail;
import com.shoppilot.common.web.BizException;
import com.shoppilot.common.web.PageResult;
import com.shoppilot.replenishment.application.dto.ReplenishmentItemBrief;
import com.shoppilot.replenishment.application.dto.ReplenishmentItemResponse;
import com.shoppilot.replenishment.application.dto.ReplenishmentPageQuery;
import com.shoppilot.replenishment.application.dto.ReplenishmentReviewResponse;
import com.shoppilot.replenishment.infrastructure.persistence.entity.ReplenishmentItemEntity;
import com.shoppilot.replenishment.infrastructure.persistence.entity.ReplenishmentReviewEntity;
import com.shoppilot.replenishment.infrastructure.persistence.mapper.ReplenishmentItemMapper;
import com.shoppilot.replenishment.infrastructure.persistence.mapper.ReplenishmentReviewMapper;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 补货建议的<b>只读</b>查询服务（M5-T07：列表 + 审核时间线）。
 *
 * <h2>通用口径</h2>
 * <ul>
 *   <li>★ <b>只读</b>：不写任何表（含审计）；</li>
 *   <li>★ <b>不跨模块 JOIN、不返回跨表名称字段</b>（{@code sku_code}/{@code sku_name}/
 *       {@code warehouse_name} 一律不返），与详情端点逐字同构；</li>
 *   <li>租户线自动过滤他团队（他团队数据在列表里表现为「查不到」，{@code total=0}，不报错）；</li>
 *   <li>分页响应字段是 {@code list}（不是 {@code items}）——契约 §2.2 的形状坑。</li>
 * </ul>
 *
 * <h2>排序</h2>
 * <p>★ 排序字段走<b>白名单映射</b>（白名单值即物理列名），绝不把用户输入拼进 {@code ORDER BY}；
 * 校验失败 ⇒ 400 {@code COM-0007}。
 */
@Service
public class ReplenishmentQueryService {

    /** 建议列表的排序白名单（物理列名；{@code API_CONTRACT §10.8}）。 */
    static final Set<String> ITEM_SORT_COLUMNS = Set.of(
            "created_at", "status", "qty", "suggested_qty", "risk_level", "expected_arrival_date");

    /** 审核时间线的排序白名单（物理列名）。 */
    static final Set<String> REVIEW_SORT_COLUMNS = Set.of("occurred_at", "id");

    /** 状态筛选白名单（{@code ReplenishmentItemStatus} 7 值）。 */
    static final Set<String> ITEM_STATUSES = Set.of("SUGGESTED", "PENDING_REVIEW", "APPROVED",
            "CONVERTED", "REJECTED", "SUPERSEDED", "CANCELLED");

    /** 风险等级筛选白名单（{@code RiskLevel} 5 值）。 */
    static final Set<String> RISK_LEVELS = Set.of("NONE", "LOW", "MEDIUM", "HIGH", "UNKNOWN");

    private final ReplenishmentItemMapper itemMapper;
    private final ReplenishmentReviewMapper reviewMapper;

    public ReplenishmentQueryService(ReplenishmentItemMapper itemMapper,
                                     ReplenishmentReviewMapper reviewMapper) {
        this.itemMapper = itemMapper;
        this.reviewMapper = reviewMapper;
    }

    /**
     * 建议列表（{@code GET /api/v1/replenishment/items}）。
     *
     * @param status    状态筛选（可空；非白名单 ⇒ 400 {@code COM-0007}）
     * @param riskLevel 风险等级筛选（可空；非白名单 ⇒ 400 {@code COM-0007}）
     * @param runId     运行筛选（可空）
     * @param page      页码（默认 1，≥1）
     * @param pageSize  每页条数（默认 20，1..200）
     * @param sort      排序（可空；形如 {@code created_at,desc}；非白名单 ⇒ 400 {@code COM-0007}）
     * @return 分页结果（{@code list/page/pageSize/total}；{@code total} 为筛选后总数）
     */
    @Transactional(readOnly = true)
    public PageResult<ReplenishmentItemResponse> list(String status, String riskLevel, Long runId,
                                                       int page, int pageSize, List<String> sort) {
        ReplenishmentPageQuery query = ReplenishmentPageQuery.of(page, pageSize, sort);
        List<ReplenishmentPageQuery.SortSpec> specs = query.validatedSort(ITEM_SORT_COLUMNS);

        QueryWrapper<ReplenishmentItemEntity> wrapper = new QueryWrapper<>();
        if (status != null) {
            if (!ITEM_STATUSES.contains(status)) {
                throw com0007("status", "status 不在允许取值内", status);
            }
            wrapper.eq("status", status);
        }
        if (riskLevel != null) {
            if (!RISK_LEVELS.contains(riskLevel)) {
                throw com0007("risk_level", "risk_level 不在允许取值内", riskLevel);
            }
            wrapper.eq("risk_level", riskLevel);
        }
        if (runId != null) {
            wrapper.eq("run_id", runId);
        }
        if (specs.isEmpty()) {
            // 默认 created_at desc
            wrapper.orderByDesc("created_at");
        } else {
            for (ReplenishmentPageQuery.SortSpec spec : specs) {
                if (spec.ascending()) {
                    wrapper.orderByAsc(spec.column());
                } else {
                    wrapper.orderByDesc(spec.column());
                }
            }
        }
        // ★ 追加 id desc 作最后 tie-breaker（稳定分页，不改变主排序语义）
        wrapper.orderByDesc("id");

        Page<ReplenishmentItemEntity> result = itemMapper.selectPage(
                new Page<>(query.getPage(), query.getPageSize()), wrapper);
        return PageResult.of(result.getTotal(), query.getPage(), query.getPageSize(),
                result.getRecords().stream().map(ReplenishmentItemResponse::of).toList());
    }

    /**
     * 审核时间线（{@code GET /api/v1/replenishment/items/{id}/reviews}）。
     *
     * <p>默认排序 {@code occurred_at asc}（时间线正序，与列表的 {@code created_at desc} 不同）。
     * 无审核记录 ⇒ 空数组 + {@code total=0}；目标不存在/跨团队 ⇒ 404 {@code RPL-8004}。
     */
    @Transactional(readOnly = true)
    public PageResult<ReplenishmentReviewResponse> reviews(long itemId, int page, int pageSize,
                                                            List<String> sort) {
        if (itemMapper.selectById(itemId) == null) {
            throw new BizException(ErrorCodes.RPL_8004, "补货建议不存在");
        }
        ReplenishmentPageQuery query = ReplenishmentPageQuery.of(page, pageSize, sort);
        List<ReplenishmentPageQuery.SortSpec> specs = query.validatedSort(REVIEW_SORT_COLUMNS);

        QueryWrapper<ReplenishmentReviewEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("replenishment_item_id", itemId);
        if (specs.isEmpty()) {
            wrapper.orderByAsc("occurred_at");
        } else {
            for (ReplenishmentPageQuery.SortSpec spec : specs) {
                if (spec.ascending()) {
                    wrapper.orderByAsc(spec.column());
                } else {
                    wrapper.orderByDesc(spec.column());
                }
            }
        }
        // tie-breaker：时间线为升序，故 id 同向（asc）
        wrapper.orderByAsc("id");

        Page<ReplenishmentReviewEntity> result = reviewMapper.selectPage(
                new Page<>(query.getPage(), query.getPageSize()), wrapper);
        return PageResult.of(result.getTotal(), query.getPage(), query.getPageSize(),
                result.getRecords().stream().map(ReplenishmentReviewResponse::of).toList());
    }


    // =====================================================================
    // 跨模块只读入口（M6-T02；DATABASE_DESIGN §7.2 #8 步骤 ① / ③）
    // =====================================================================

    /**
     * 按 ID 取建议摘要（{@code §7.2 #8} 步骤 ①：校验「存在 + 属本团队」）。
     *
     * <p>走租户线 ⇒ 他团队的建议<b>查不到</b>（返回集合中缺失该项），调用方据此报
     * {@code RPL-8004}（404）——不泄漏他团队是否存在。
     *
     * @param itemIds 建议 ID（去重、保持传入顺序）
     * @return 命中的建议摘要（可能少于入参个数 = 有 ID 不存在或跨团队）
     */
    @Transactional(readOnly = true)
    public List<ReplenishmentItemBrief> getItems(Collection<Long> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return List.of();
        }
        List<Long> ids = itemIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, ReplenishmentItemEntity> byId = new HashMap<>();
        for (ReplenishmentItemEntity entity : itemMapper.selectBatchIds(ids)) {
            byId.put(entity.getId(), entity);
        }
        // 保持调用方传入顺序（建单行号与审计顺序稳定可测）
        return ids.stream().map(byId::get).filter(Objects::nonNull)
                .map(ReplenishmentItemBrief::of).toList();
    }

    /**
     * 同口径候选集（{@code §7.2 #8} 步骤 ③）：{@code team_id × warehouse_id × sku_id} 下的
     * <b>{@code APPROVED}</b> 建议（<b>含本次入参之外的其他建议</b>）。
     *
     * <p>该结果是冲突集 C 的<b>上界</b>；{@code purchasing} 再做「未转采购」过滤（{@code NOT EXISTS}）
     * 得到真正的冲突集（{@code §7.2 #8} ③b）。
     *
     * <p>★ 入参带 {@code teamId}（与 {@code §7.2 #8} 签名一致）：租户线已按当前团队过滤，
     * 该参数用于<b>显式同口径断言</b>（防止上下文与入参不一致时静默按另一团队查询）。
     *
     * @param teamId      团队（须与 {@code TeamContext} 一致）
     * @param warehouseId 仓库
     * @param skuIds      SKU 集合
     * @return 命中的 {@code APPROVED} 建议摘要（按 {@code id} 升序，保证检测结果稳定）
     */
    @Transactional(readOnly = true)
    public List<ReplenishmentItemBrief> findByTeamWhSkuApproved(Long teamId, Long warehouseId,
                                                                Collection<Long> skuIds) {
        if (teamId == null || warehouseId == null || skuIds == null || skuIds.isEmpty()) {
            return List.of();
        }
        List<Long> ids = skuIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return itemMapper.selectList(Wrappers.<ReplenishmentItemEntity>lambdaQuery()
                        .eq(ReplenishmentItemEntity::getTeamId, teamId)
                        .eq(ReplenishmentItemEntity::getWarehouseId, warehouseId)
                        .in(ReplenishmentItemEntity::getSkuId, ids)
                        .eq(ReplenishmentItemEntity::getStatus, "APPROVED")
                        .orderByAsc(ReplenishmentItemEntity::getId))
                .stream().map(ReplenishmentItemBrief::of).toList();
    }

    private static BizException com0007(String field, String message, Object rejected) {
        return BizException.withDetails(ErrorCodes.COM_0007, "分页或排序参数不合法",
                List.of(new ApiErrorDetail(field, message, rejected)));
    }
}