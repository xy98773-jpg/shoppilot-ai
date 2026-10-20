package com.shoppilot.replenishment.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.ApiErrorDetail;
import com.shoppilot.common.web.BizException;
import com.shoppilot.common.web.PageResult;
import com.shoppilot.replenishment.application.dto.ReplenishmentItemResponse;
import com.shoppilot.replenishment.application.dto.ReplenishmentPageQuery;
import com.shoppilot.replenishment.application.dto.ReplenishmentReviewResponse;
import com.shoppilot.replenishment.infrastructure.persistence.entity.ReplenishmentItemEntity;
import com.shoppilot.replenishment.infrastructure.persistence.entity.ReplenishmentReviewEntity;
import com.shoppilot.replenishment.infrastructure.persistence.mapper.ReplenishmentItemMapper;
import com.shoppilot.replenishment.infrastructure.persistence.mapper.ReplenishmentReviewMapper;
import java.util.List;
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

    private static BizException com0007(String field, String message, Object rejected) {
        return BizException.withDetails(ErrorCodes.COM_0007, "分页或排序参数不合法",
                List.of(new ApiErrorDetail(field, message, rejected)));
    }
}
