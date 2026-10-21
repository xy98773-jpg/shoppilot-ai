package com.shoppilot.audit.application;

import com.shoppilot.audit.application.dto.AuditLogItem;
import com.shoppilot.audit.application.dto.AuditLogQuery;
import com.shoppilot.audit.domain.model.AuditResult;
import com.shoppilot.audit.infrastructure.persistence.entity.AuditLogEntity;
import com.shoppilot.audit.infrastructure.persistence.mapper.AuditLogMapper;
import com.shoppilot.common.error.ErrorCodes;
import com.shoppilot.common.web.ApiErrorDetail;
import com.shoppilot.common.web.BizException;
import com.shoppilot.common.web.PageResult;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审计日志只读查询服务（{@code M6-T09} / {@code F-AUD-02}，{@code API_CONTRACT §10.10}）。
 *
 * <h2>边界</h2>
 * <ul>
 *   <li><b>只读</b>：只调 {@link AuditLogMapper} 的两个 SELECT，<b>不</b>写库、<b>不</b>写审计
 *       （{@code audit_logs} 仅追加，{@code DATABASE_DESIGN §6-J.4}）；</li>
 *   <li><b>团队隔离由租户线保证</b>：Mapper 不加 {@code @InterceptorIgnore}，SQL 自动追加
 *       {@code team_id = TeamContext}；本层<b>不</b>接受、也不透传任何客户端团队参数；</li>
 *   <li><b>SQL 全部在 Mapper</b>：本层只做参数校验与组装，不拼 SQL（{@code B0-9}）。</li>
 * </ul>
 *
 * <h2>参数校验（全部 {@code 400 COM-0001}，不走 {@code COM-0007}）</h2>
 * 本批裁定审计查询的非法参数统一 {@code COM-0001}（{@code B0-4}）：
 * <ul>
 *   <li>{@code result} 非 {@code SUCCESS}/{@code FAILURE}；</li>
 *   <li>给了 {@code biz_id} 但缺 {@code biz_type}（多态关联必须带类型才唯一）；</li>
 *   <li>{@code date_from}/{@code date_to} 非 {@code YYYY-MM-DD}，或 {@code date_from > date_to}；</li>
 *   <li>{@code sort} 不是 {@code occurred_at,asc} / {@code occurred_at,desc}。</li>
 * </ul>
 *
 * <h2>日期语义（{@code B0-4}）</h2>
 * {@code date_from}/{@code date_to} 是 <b>UTC 自然日</b>，作用列是 {@code occurred_at}：
 * 区间为 {@code [date_from 00:00, date_to + 1d 00:00)}——即 {@code date_to} <b>当天本身也包含</b>，
 * 与「按天筛选」的用户直觉一致，同时避免 {@code 23:59:59.999} 的毫秒边界遗漏。
 */
@Service
public class AuditLogQueryService {

    private final AuditLogMapper auditLogMapper;

    public AuditLogQueryService(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    /**
     * 分页查询当前团队的审计日志。
     *
     * <p>排序固定 {@code occurred_at, id} 同向（缺省 DESC）；同毫秒记录用 {@code id} 稳定排序，
     * 保证翻页不重不漏。
     *
     * @param query 已夹取分页参数的查询条件（非 {@code null}）
     * @return 分页结果（{@code list/page/pageSize/total}），字段口径见 {@code API_CONTRACT §2.2}
     * @throws BizException {@code COM-0001} 任一筛选/排序参数非法
     */
    @Transactional(readOnly = true)
    public PageResult<AuditLogItem> listAuditLogs(AuditLogQuery query) {
        Filters filters = Filters.from(query);

        long total = auditLogMapper.selectAuditLogCount(filters.bizType, filters.bizId, filters.action,
                filters.result, filters.operatorId, filters.dateFrom, filters.dateToBefore);
        if (total == 0) {
            return PageResult.of(0, query.getPage(), query.getPageSize(), List.of());
        }

        long offset = (long) (query.getPage() - 1) * query.getPageSize();
        List<AuditLogEntity> rows = auditLogMapper.selectAuditLogPage(filters.bizType, filters.bizId,
                filters.action, filters.result, filters.operatorId, filters.dateFrom, filters.dateToBefore,
                filters.ascending, offset, query.getPageSize());
        List<AuditLogItem> list = new ArrayList<>(rows.size());
        for (AuditLogEntity row : rows) {
            list.add(AuditLogItem.of(row));
        }
        return PageResult.of(total, query.getPage(), query.getPageSize(), list);
    }

    // ---------------------------------------------------------------------
    // 参数校验 / 归一化（一次算好，count 与 page 两个 SQL 共用同一组值）
    // ---------------------------------------------------------------------

    /** 归一化并校验后的筛选条件（供 count / page 两条 SQL 复用，避免两处口径漂移）。 */
    private static final class Filters {

        private final String bizType;
        private final Long bizId;
        private final String action;
        private final String result;
        private final Long operatorId;
        private final LocalDateTime dateFrom;
        private final LocalDateTime dateToBefore;
        private final boolean ascending;

        private Filters(String bizType, Long bizId, String action, String result, Long operatorId,
                        LocalDateTime dateFrom, LocalDateTime dateToBefore, boolean ascending) {
            this.bizType = bizType;
            this.bizId = bizId;
            this.action = action;
            this.result = result;
            this.operatorId = operatorId;
            this.dateFrom = dateFrom;
            this.dateToBefore = dateToBefore;
            this.ascending = ascending;
        }

        private static Filters from(AuditLogQuery query) {
            String bizType = blankToNull(query.getBizType());
            Long bizId = query.getBizId();
            // ★ biz_id 是多态关联（跨表），脱离 biz_type 无唯一语义 → 显式拒绝，不猜测
            if (bizId != null && bizType == null) {
                throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                        List.of(new ApiErrorDetail("biz_type", "提供 biz_id 时必须同时提供 biz_type",
                                String.valueOf(bizId))));
            }
            String action = blankToNull(query.getAction());
            String result = normalizeResult(query.getResult());
            Long operatorId = query.getOperatorId();

            LocalDate fromDate = parseDate(query.getDateFrom(), "date_from");
            LocalDate toDate = parseDate(query.getDateTo(), "date_to");
            if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
                throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                        List.of(new ApiErrorDetail("date_from", "date_from 不能晚于 date_to",
                                query.getDateFrom())));
            }
            LocalDateTime dateFrom = fromDate == null ? null : fromDate.atStartOfDay();
            // 右开区间：date_to 当天整天都包含
            LocalDateTime dateToBefore = toDate == null ? null : toDate.plusDays(1).atStartOfDay();

            return new Filters(bizType, bizId, action, result, operatorId, dateFrom, dateToBefore,
                    resolveAscending(query.getSort()));
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }

        /** {@code result} 只接受枚举字面量（与 DB CHECK {@code ck_al_result} 同一取值域）。 */
        private static String normalizeResult(String raw) {
            String value = blankToNull(raw);
            if (value == null) {
                return null;
            }
            for (AuditResult allowed : AuditResult.values()) {
                if (allowed.name().equals(value)) {
                    return value;
                }
            }
            throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(new ApiErrorDetail("result", "仅支持 SUCCESS / FAILURE", value)));
        }

        private static LocalDate parseDate(String raw, String field) {
            String value = blankToNull(raw);
            if (value == null) {
                return null;
            }
            try {
                return LocalDate.parse(value);
            } catch (DateTimeParseException ex) {
                throw BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                        List.of(new ApiErrorDetail(field, "日期格式必须为 YYYY-MM-DD", value)));
            }
        }

        /**
         * 解析 {@code sort}：只接受 {@code occurred_at,asc} / {@code occurred_at,desc}；
         * 缺省 {@code occurred_at,desc}。返回 {@code true} 表示 ASC。
         *
         * <p>Spring 把 {@code sort=a,b} 与重复参数都会绑定为 {@code List<String>}，故先按逗号摊平。
         * <b>方向值从不进入 SQL 文本</b>：Mapper 用 {@code <choose>} 在两个固定片段间二选一。
         */
        private static boolean resolveAscending(List<String> sort) {
            List<String> tokens = new ArrayList<>();
            for (String raw : sort) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                for (String part : raw.split(",")) {
                    if (!part.isBlank()) {
                        tokens.add(part.trim().toLowerCase(Locale.ROOT));
                    }
                }
            }
            if (tokens.isEmpty()) {
                return false; // 缺省 occurred_at DESC（B0-4）
            }
            if (tokens.size() != 2 || !"occurred_at".equals(tokens.get(0))) {
                throw invalidSort(sort);
            }
            String direction = tokens.get(1);
            if ("asc".equals(direction)) {
                return true;
            }
            if ("desc".equals(direction)) {
                return false;
            }
            throw invalidSort(sort);
        }

        private static BizException invalidSort(List<String> raw) {
            return BizException.withDetails(ErrorCodes.COM_0001, "请求参数校验失败",
                    List.of(new ApiErrorDetail("sort", "仅支持 occurred_at,asc / occurred_at,desc",
                            String.join(",", raw))));
        }
    }
}
